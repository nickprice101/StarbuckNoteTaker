"""
Complete Note Classifier Training and Deployment
Trains model, validates with examples, exports to TFLite
Compatible with TensorFlow 2.16.1
"""
import os
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
os.environ['TF_USE_LEGACY_KERAS'] = '1'  # Use tf-keras instead of Keras 3
os.environ['TF_ENABLE_EAGER_CLIENT_STREAMING_ENQUEUE'] = 'False'

import random
import shutil
from pathlib import Path
import sys
import json
import re
from datetime import datetime

import tensorflow as tf
from tensorflow.lite.python import schema_py_generated as schema_fb
import flatbuffers
import numpy as np
from sklearn.model_selection import train_test_split

# Initialize seeds
random.seed(42)
np.random.seed(42)
tf.random.set_seed(42)

print(f"Using TensorFlow {tf.__version__}")

def _assert_full_connected_compatibility(model_content: bytes, max_version: int = 11) -> None:
    """Ensure generated TFLite model keeps FULLY_CONNECTED ops within range."""

    tflite_model = schema_fb.Model.GetRootAsModel(model_content, 0)
    for idx in range(tflite_model.OperatorCodesLength()):
        op_code = tflite_model.OperatorCodes(idx)
        if op_code.BuiltinCode() == schema_fb.BuiltinOperator.FULLY_CONNECTED:
            if op_code.Version() > max_version:
                raise RuntimeError(
                    "Exported model requires FULLY_CONNECTED op version "
                    f"{op_code.Version()}, which exceeds the maximum supported version."
                )


def _downgrade_fully_connected_ops(
    model_content: bytes, target_version: int = 11
) -> tuple[bytes, bool]:
    """Downgrade FULLY_CONNECTED ops to a lower version when safe.

    Returns a tuple of the potentially modified model buffer and a boolean flag
    indicating whether any operator codes were rewritten.
    """

    model_fb = schema_fb.Model.GetRootAsModel(model_content, 0)
    needs_downgrade = False
    for idx in range(model_fb.OperatorCodesLength()):
        op_code = model_fb.OperatorCodes(idx)
        if (
            op_code.BuiltinCode() == schema_fb.BuiltinOperator.FULLY_CONNECTED
            and op_code.Version() > target_version
        ):
            needs_downgrade = True
            break

    if not needs_downgrade:
        return model_content, False

    model_t = schema_fb.ModelT.InitFromObj(model_fb)

    # Ensure we only downgrade when the model does not rely on newer attributes.
    for subgraph in model_t.subgraphs:
        for operator in subgraph.operators:
            opcode = model_t.operatorCodes[operator.opcodeIndex]
            if opcode.builtinCode != schema_fb.BuiltinOperator.FULLY_CONNECTED:
                continue

            if operator.builtinOptionsType == schema_fb.BuiltinOptions.FullyConnectedOptions:
                options = operator.builtinOptions
                # weightsFormat other than DEFAULT requires newer interpreter support.
                if getattr(
                    options,
                    "weightsFormat",
                    schema_fb.FullyConnectedOptionsWeightsFormat.DEFAULT,
                ) != schema_fb.FullyConnectedOptionsWeightsFormat.DEFAULT:
                    raise RuntimeError(
                        "Cannot downgrade FULLY_CONNECTED op that depends on non-default "
                        "weights format. Update the Android TensorFlow Lite runtime instead."
                    )

    for op_code in model_t.operatorCodes:
        if (
            op_code.builtinCode == schema_fb.BuiltinOperator.FULLY_CONNECTED
            and op_code.version > target_version
        ):
            op_code.version = target_version

    builder = flatbuffers.Builder(0)
    model_offset = model_t.Pack(builder)
    builder.Finish(model_offset, b"TFL3")
    return bytes(builder.Output()), True


def _summarise_fully_connected_versions(model_content: bytes) -> tuple[int, int]:
    """Return count and max version for FULLY_CONNECTED operators."""

    model_fb = schema_fb.Model.GetRootAsModel(model_content, 0)
    count = 0
    max_version = 0
    seen_opcode_indices: set[int] = set()

    # OperatorCodes provide declared versions, but double-checking operators
    # ensures the summary reflects what the runtime will request at execution
    # time (some graphs keep unused OperatorCodes around).
    for idx in range(model_fb.OperatorCodesLength()):
        op_code = model_fb.OperatorCodes(idx)
        if op_code.BuiltinCode() == schema_fb.BuiltinOperator.FULLY_CONNECTED:
            count += 1
            max_version = max(max_version, op_code.Version())
            seen_opcode_indices.add(idx)

    model_t = schema_fb.ModelT.InitFromObj(model_fb)
    for subgraph in model_t.subgraphs:
        for operator in subgraph.operators:
            if operator.opcodeIndex in seen_opcode_indices:
                op_code = model_t.operatorCodes[operator.opcodeIndex]
                max_version = max(max_version, op_code.version)

    return count, max_version


def _describe_model_compatibility(model_content: bytes, *, label: str) -> None:
    """Print compatibility details for a TFLite buffer and enforce constraints."""

    fc_count, fc_max_version = _summarise_fully_connected_versions(model_content)
    print(
        f"{label}: FULLY_CONNECTED operators present: "
        f"{fc_count} (highest version v{fc_max_version})"
    )

    if fc_count:
        _assert_full_connected_compatibility(model_content)

    declared_runtime = _extract_declared_min_runtime(model_content)
    if declared_runtime:
        print(f"{label}: Metadata min_runtime_version: {declared_runtime}")


def _extract_declared_min_runtime(model_content: bytes) -> str | None:
    """Read the min_runtime_version metadata if present."""

    model_fb = schema_fb.Model.GetRootAsModel(model_content, 0)
    model_t = schema_fb.ModelT.InitFromObj(model_fb)
    if not model_t.metadata:
        return None

    for meta in model_t.metadata:
        if getattr(meta, "name", b"") == b"min_runtime_version":
            buffer = model_t.buffers[meta.buffer]
            if buffer.data:
                try:
                    return bytes(buffer.data).decode("utf-8")
                except UnicodeDecodeError:
                    return None
    return None

print("="*80)
print("NOTE CLASSIFIER - COMPLETE PIPELINE")
print("="*80)

SCRIPT_DIR = Path(__file__).resolve().parent
# All generated artifacts must stay alongside this script so the Android assets
# mirror can simply copy them without juggling multiple output locations.
ASSETS_DIR = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
from training_data_large import category_examples  # noqa: E402

# Provide a quick diagnostic for the currently bundled model before training so
# regressions are caught even when the pipeline is not re-run end-to-end.
existing_asset = ASSETS_DIR / "note_classifier.tflite"
if existing_asset.exists():
    print("\n[0/5] Inspecting existing TensorFlow Lite asset...")
    try:
        _describe_model_compatibility(existing_asset.read_bytes(), label="Existing asset")
    except RuntimeError as exc:
        print(f"Existing asset is incompatible with the Android runtime: {exc}")

# Step 1: Load Data
print("\n[1/5] Loading training data...")

NOTE_TYPES = [
    "FOOD_RECIPE", "PERSONAL_DAILY_LIFE", "FINANCE_LEGAL", "SELF_IMPROVEMENT",
    "HEALTH_WELLNESS", "EDUCATION_LEARNING", "HOME_FAMILY", "WORK_PROJECT",
    "MEETING_RECAP", "SHOPPING_LIST", "GENERAL_CHECKLIST", "REMINDER",
    "TRAVEL_LOG", "CREATIVE_WRITING", "TECHNICAL_REFERENCE"
]

NUM_CATEGORIES = len(NOTE_TYPES)

all_notes = []
all_labels = []
for idx, cat in enumerate(NOTE_TYPES):
    examples = category_examples[cat]
    # Extract the 'note' field from each dictionary
    notes_text = [ex['note'] if isinstance(ex, dict) else ex for ex in examples]
    all_notes.extend(notes_text)
    all_labels.extend([idx] * len(examples))

print(f"Loaded {len(all_notes)} examples ({len(all_notes)//NUM_CATEGORIES} per category)")

# Step 2: Train/Val Split
print("\n[2/5] Splitting data...")
from sklearn.model_selection import train_test_split
X_train, X_val, y_train, y_val = train_test_split(
    all_notes, all_labels, test_size=0.25, random_state=42, stratify=all_labels
)
print(f"Train: {len(X_train)}, Val: {len(X_val)}")

# Step 3: Build and Train Model
print("\n[3/5] Building and training model...")

AUTOTUNE = tf.data.AUTOTUNE
# Focus on better text representation rather than complex architecture
vectorizer = tf.keras.layers.TextVectorization(max_tokens=10000, output_sequence_length=100)
# Create TensorFlow dataset directly from the lists
train_ds = tf.data.Dataset.from_tensor_slices(
    (tf.constant(X_train, dtype=tf.string), tf.constant(y_train, dtype=tf.int32))
)
val_ds = tf.data.Dataset.from_tensor_slices(
    (tf.constant(X_val, dtype=tf.string), tf.constant(y_val, dtype=tf.int32))
)
vectorizer.adapt(train_ds.map(lambda x, y: x))

inputs = tf.keras.Input(shape=(1,), dtype=tf.string)
x = vectorizer(inputs)
# Simpler but effective architecture
x = tf.keras.layers.Embedding(10000, 256, mask_zero=True)(x)
# Triple pooling for richer features
avg_pool = tf.keras.layers.GlobalAveragePooling1D()(x)
max_pool = tf.keras.layers.GlobalMaxPooling1D()(x)
x = tf.keras.layers.Concatenate()([avg_pool, max_pool])
# Simpler network - easier to train
x = tf.keras.layers.Dense(512, 'relu')(x)
x = tf.keras.layers.Dropout(0.5)(x)
x = tf.keras.layers.Dense(256, 'relu')(x)
x = tf.keras.layers.Dropout(0.4)(x)
x = tf.keras.layers.Dense(128, 'relu')(x)
x = tf.keras.layers.Dropout(0.3)(x)
outputs = tf.keras.layers.Dense(NUM_CATEGORIES, 'softmax')(x)

model = tf.keras.Model(inputs, outputs)

# Use standard settings
optimizer = tf.keras.optimizers.Adam(learning_rate=0.001)
model.compile(
    optimizer=optimizer, 
    loss='sparse_categorical_crossentropy', 
    metrics=['accuracy'],
    run_eagerly=True
)

# Prepare datasets - more aggressive shuffling and larger batch
train_ds_batched = train_ds.shuffle(2000).batch(32).cache().prefetch(AUTOTUNE)
val_ds_batched = val_ds.batch(32).cache().prefetch(AUTOTUNE)

# Train for more epochs with very patient early stopping
callbacks = [
    tf.keras.callbacks.EarlyStopping(monitor='val_accuracy', patience=30, restore_best_weights=True, mode='max'),
    tf.keras.callbacks.ReduceLROnPlateau(monitor='val_accuracy', factor=0.5, patience=10, min_lr=1e-6, verbose=1, mode='max')
]

history = model.fit(
    train_ds_batched,
    validation_data=val_ds_batched,
    epochs=200,
    callbacks=callbacks,
    verbose=2
)

best_acc = max(history.history['val_accuracy'])
print(f"\nBest validation accuracy: {best_acc*100:.2f}%")

# Step 4: Test Model
print("\n[4/5] Testing model...")

tests = [
    ("Homemade pasta recipe: mix 2 cups flour with 3 eggs until dough forms, knead for 10 minutes until smooth and elastic, roll thin with pasta machine, cut into fettuccine strips, boil in salted water for 3 minutes.", "FOOD_RECIPE"),
    ("Had wonderful coffee with friend Sarah at the new downtown cafe, caught up on life updates and weekend plans, she recommended a great book series I should check out, really enjoyed the quality time together.", "PERSONAL_DAILY_LIFE"),
    ("Filed annual tax return online through IRS website using Form 1040, included all W-2 income and freelance earnings from 1099 forms, claimed standard deduction, expecting refund of $1,250 within 21 days.", "FINANCE_LEGAL"),
    ("Started daily meditation practice using guided app, committed to 10 minutes each morning before work to observe thoughts without judgment, already noticing reduced stress levels and improved focus after one week.", "SELF_IMPROVEMENT"),
    ("Morning run: jogged 5 miles around the neighborhood park at steady 9-minute per mile pace, felt great to get fresh air and exercise after sitting at desk all day, planning to make this a regular routine.", "HEALTH_WELLNESS"),
    ("Completed online course module on advanced Python programming covering object-oriented design patterns, inheritance and polymorphism concepts, watched 4 hours of video lectures, passed final quiz with 92% score.", "EDUCATION_LEARNING"),
    ("Fixed leaky bathroom faucet that had been dripping for weeks, turned off water supply valve, removed handle and replaced worn rubber washer, reassembled components, tested for leaks, no more dripping sound.", "HOME_FAMILY"),
    ("Project Alpha weekly status update: development progressing on schedule at 75% complete, integration testing planned for next week, minor styling adjustments needed, stakeholders pleased with current progress and demos.", "WORK_PROJECT"),
    ("Team standup meeting recap: discussed sprint progress and current blockers, aligned on priorities for upcoming deliverables, identified two issues requiring escalation to senior management, scheduled follow-up session for Tuesday.", "MEETING_RECAP"),
    ("Grocery shopping list for the week: need 2 gallons whole milk for breakfast cereal and coffee, dozen large eggs for weekend cooking, loaf of whole wheat bread for sandwiches, sharp cheddar cheese block for snacks.", "SHOPPING_LIST"),
    ("Important reminder: call dentist office tomorrow morning to schedule overdue six-month cleaning and checkup appointment, mention mild tooth sensitivity on lower left side, request appointment during lunch hour if possible to minimize work disruption.", "REMINDER"),
    ("Paris vacation travel log: spent amazing day exploring the Louvre Museum viewing famous artworks including Mona Lisa, climbed to top of Eiffel Tower for sunset photography with incredible city views, enjoyed authentic croissants at charming Montmartre cafe.", "TRAVEL_LOG"),
    ("Creative writing piece: The old lighthouse stood alone on the rocky cliff overlooking the stormy sea, its beacon cutting through the fog like a sword through darkness, warning sailors of the dangerous shoals below with each slow rotation.", "CREATIVE_WRITING"),
    ("Git rebase technical reference: command 'git rebase -i HEAD~3' allows interactive rebase to edit, reorder, or squash the last three commits interactively before pushing to remote branch, opens editor to modify commit list with various options.", "TECHNICAL_REFERENCE")
]

correct = 0
for note, expected in tests:
    pred = model.predict(tf.constant([note]), verbose=0)
    category = NOTE_TYPES[np.argmax(pred[0])]
    status = "OK" if category == expected else "XX"
    print(f"[{status}] {expected:25s} -> {category}")
    if category == expected:
        correct += 1

test_acc = (correct/len(tests))*100
print(f"\nTest Accuracy: {correct}/{len(tests)} = {test_acc:.1f}%")

# Enhanced Summary Generation Functions
def smart_truncate(text, max_length=140):
    """
    Truncate text to max_length while ensuring complete sentences.
    Breaks at sentence boundaries if possible.
    """
    if len(text) <= max_length:
        return text
    
    # Try to break at sentence end (period, exclamation, question mark)
    truncated = text[:max_length]
    
    # Find last sentence-ending punctuation
    last_period = max(truncated.rfind('.'), truncated.rfind('!'), truncated.rfind('?'))
    
    if last_period > max_length * 0.7:  # If we found one in the last 30%
        return text[:last_period + 1].strip()
    
    # Otherwise, break at last complete word
    last_space = truncated.rfind(' ')
    if last_space > 0:
        return truncated[:last_space].strip()
    
    return truncated.strip()

def generate_enhanced_summary(note_text, category):
    """
    Generate natural language summary in format: [content type] [context] [example/subject]
    Limited to 140 characters with complete sentences.
    """
    category_friendly = category.replace("_", " ").title()
    
    # Parse structure: look for colon-separated context and content
    if ":" in note_text:
        parts = note_text.split(":", 1)
        context_part = parts[0].strip()
        content_part = parts[1].strip()
    else:
        context_part = ""
        content_part = note_text.strip()
    
    # Detect characteristics
    is_list = content_part.count(',') >= 2 or content_part.count(' and ') >= 2
    has_numbers = bool(re.search(r'\d+', content_part))
    word_count = len(content_part.split())
    is_long = word_count > 50
    
    # Extract key subjects (capitalized words, important nouns)
    subjects = re.findall(r'\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b', note_text)
    
    # Category-specific summary generation
    if category == "SHOPPING_LIST":
        items = [item.strip() for item in re.split(r',| and ', content_part)]
        # Clean items
        clean_items = []
        for item in items[:4]:
            words = item.split()
            product = ' '.join([w for w in words if not any(c.isdigit() for c in w) and 
                              w.lower() not in ['need', 'get', 'buy', 'pick', 'for', 'the', 'a', 'an']])[:30]
            if product:
                clean_items.append(product.strip())
        
        count = len(items)
        if count <= 3:
            summary = f"Shopping list for {', '.join(clean_items)}"
        else:
            summary = f"Shopping list with {count} items including {', '.join(clean_items[:2])} and more"
        return smart_truncate(summary)

    elif category == "GENERAL_CHECKLIST":
        raw_items = [
            re.sub(r'^[-\u2022\u2023\u25CF\u25AA\u25E6\*]+\s*', '', item.strip())
            for item in re.split(r'(?:,|;|\band\b|\n)', content_part, flags=re.IGNORECASE)
        ]
        clean_items = [segment.strip(" .") for segment in raw_items if segment.strip(" .")]

        label = context_part.lower() if context_part else "key tasks"
        label = re.sub(r'\b(checklist|list|tasks)\b', '', label).strip()
        if not label:
            label = "key tasks"

        count = len(clean_items)
        if count >= 3:
            summary = f"Checklist for {label} with {count} tasks including {clean_items[0]} and {clean_items[1]}"
        elif count == 2:
            summary = f"Checklist for {label} covering {clean_items[0]} and {clean_items[1]}"
        elif count == 1:
            summary = f"Checklist for {label} highlighting {clean_items[0]}"
        else:
            summary = f"Checklist outlining {label}"

        return smart_truncate(summary)

    elif category == "FOOD_RECIPE":
        if context_part:
            recipe_name = context_part.lower().replace(' recipe', '').replace(' how to make', '')
            if is_long:
                summary = f"Recipe with detailed instructions for preparing {recipe_name}"
            else:
                summary = f"Recipe for {recipe_name}"
        else:
            summary = f"Recipe note with cooking instructions"
        return smart_truncate(summary)
    
    elif category == "MEETING_RECAP":
        meeting_type = context_part.lower() if context_part else "team meeting"
        key_topics = []
        
        if "assigned" in content_part.lower() or "action" in content_part.lower():
            key_topics.append("task assignments")
        if "discussed" in content_part.lower() or "aligned" in content_part.lower():
            key_topics.append("key decisions")
        if "scheduled" in content_part.lower():
            key_topics.append("scheduling")
        
        if key_topics:
            summary = f"Meeting note on {meeting_type} covering {' and '.join(key_topics[:2])}"
        else:
            summary = f"Meeting recap documenting {meeting_type} discussions"
        return smart_truncate(summary)
    
    elif category == "TRAVEL_LOG":
        destination = None
        if subjects:
            for subj in subjects:
                if subj not in ['Woke', 'Packed', 'Noted', 'Explored', 'Visited']:
                    destination = subj
                    break
        
        if destination:
            summary = f"Travel log documenting journey to {destination} with experiences and observations"
        elif context_part:
            summary = f"Travel experience note about {context_part.lower()}"
        else:
            summary = f"Travel log entry capturing experiences and insights"
        return smart_truncate(summary)
    
    elif category == "WORK_PROJECT":
        project_name = context_part if context_part else "project work"
        
        if "milestone" in content_part.lower() or "deliverable" in content_part.lower():
            summary = f"Work project note for {project_name} outlining milestones and deliverables"
        elif "status" in content_part.lower() or "update" in content_part.lower():
            summary = f"Project status update on {project_name}"
        else:
            summary = f"Work project tracking progress on {project_name}"
        return smart_truncate(summary)
    
    elif category == "TECHNICAL_REFERENCE":
        procedure = context_part.lower() if context_part else "system procedure"
        
        if "command" in content_part.lower() or "code" in content_part.lower():
            summary = f"Technical reference documenting command syntax for {procedure}"
        else:
            summary = f"Technical documentation with step-by-step procedures for {procedure}"
        return smart_truncate(summary)
    
    elif category == "CREATIVE_WRITING":
        theme = context_part.lower() if context_part else "creative piece"
        
        if "poetry" in note_text.lower() or "poetic" in note_text.lower():
            summary = f"Creative writing: poetic composition about {theme}"
        elif "story" in note_text.lower():
            summary = f"Creative writing: story development exploring {theme}"
        else:
            summary = f"Creative writing piece: {theme}"
        return smart_truncate(summary)
    
    elif category == "FINANCE_LEGAL":
        activity = context_part.lower() if context_part else "financial activity"
        
        amounts = re.findall(r'\$[\d,]+(?:\.\d{2})?', content_part)
        if amounts:
            summary = f"Financial record for {activity} involving {amounts[0]}"
        else:
            summary = f"Financial note documenting {activity}"
        return smart_truncate(summary)
    
    elif category == "HEALTH_WELLNESS":
        activity = context_part.lower() if context_part else "wellness activity"
        
        metrics = re.findall(r'\d+(?:\.\d+)?\s*(?:miles|minutes|hours|lbs|kg|calories)', content_part.lower())
        if metrics:
            summary = f"Health tracking note for {activity} recording {metrics[0]}"
        else:
            summary = f"Health and wellness note about {activity}"
        return smart_truncate(summary)
    
    elif category == "EDUCATION_LEARNING":
        subject = context_part.lower() if context_part else "learning activity"
        
        if "completed" in content_part.lower() or "passed" in content_part.lower():
            summary = f"Learning milestone: completed {subject}"
        else:
            summary = f"Educational note on {subject} and ongoing development"
        return smart_truncate(summary)
    
    elif category == "REMINDER":
        # Extract action cleanly, limiting to key information
        action = content_part.strip()
        if action.lower().startswith('to '):
            action = action[3:]
        
        # Smart truncation for reminders - keep complete phrases
        if len(action) > 100:
            # Find a natural break point (comma, 'and', etc.)
            for break_point in [',', ' and ', '; ']:
                if break_point in action[:100]:
                    action = action[:action[:100].rfind(break_point)]
                    break
            else:
                # No natural break, truncate at word boundary
                action = action[:100].rsplit(' ', 1)[0]
        
        summary = f"Reminder to {action}"
        return smart_truncate(summary)
    
    elif category == "PERSONAL_DAILY_LIFE":
        activity = context_part.lower() if context_part else "daily activity"
        summary = f"Daily life note about {activity}"
        return smart_truncate(summary)
    
    elif category == "HOME_FAMILY":
        topic = context_part.lower() if context_part else "family matter"
        
        if is_list:
            summary = f"Family coordination note regarding {topic} with multiple items"
        else:
            summary = f"Family and home note about {topic}"
        return smart_truncate(summary)
    
    elif category == "SELF_IMPROVEMENT":
        focus = context_part.lower() if context_part else "personal development"
        summary = f"Personal growth note on {focus} for self-improvement"
        return smart_truncate(summary)
    
    else:
        # Generic intelligent summary
        if context_part:
            summary = f"{category_friendly} note about {context_part.lower()}"
        else:
            first_words = ' '.join(content_part.split()[:10])
            summary = f"{category_friendly}: {first_words}..."
        return smart_truncate(summary)

# Step 4.5: Generate Enhanced Summaries for Validation
print("\n[4.5/5] Enhanced Summary Validation Examples...")
print("="*80)

validation_examples = [
    "Shift meeting recap: aligned on mobile order staging flow, assigned Riley to monitor warming oven temps, noted need to reorder grande lids.",
    "Weekly grocery run: oat milk 3 cartons, almond butter 2 jars, spinach, blueberries, cold brew filters, biodegradable soap refill.",
    "Weekend maintenance checklist: drain water heater, clean dryer vent, replace HVAC filters, test smoke alarms, and document readings in log.",
    "Seattle to Portland trip: departed 8:15am on Amtrak Cascades, tasted seasonal lattes at Pioneer Courthouse store, noted crowd insights for merchandising deck.",
    "Poetic sketch: latte art swan mirrors sunrise commute, foam feathers tracing ambitions of the opening crew.",
    "Machine maintenance SOP: purge Mastrena lines for 6 seconds, run detergent cycle nightly, log pressure readings in Equipment Tracker tab.",
    "Thai green curry recipe: toast 2 tbsp green curry paste in coconut oil, add coconut milk and simmer, incorporate chicken pieces and bamboo shoots.",
    "Budget review session: reconciled March cafe tips with bank deposit, set aside $120 for partner appreciation gifts, flagged IRS letter.",
    "Morning jog: ran 4 miles around Green Lake, maintained 9-minute pace, felt energized after sitting at desk all day.",
    "Project Voyager implementation: mapped out rollout timeline for new cold foam station equipment across 50 locations, assigned hardware checks to Malik.",
    "Call dentist tomorrow: schedule six-month cleaning appointment, mention tooth sensitivity on lower left side, request lunch hour slot.",
    "Vacation journal: explored Kyoto coffee scene visiting five specialty cafes, sampled pour-over at Arabica with view of Yasaka Pagoda.",
    "git rebase command: interactive rebase using git rebase -i HEAD~3 allows editing and reordering last three commits interactively.",
    "Price comparison memo: Model X laptop offers 32GB RAM at $1,799 while Model Y adds OLED display for $1,950 and extends warranty coverage.",
    "Novel chapter development: produced 3,500 words of confrontation scene between protagonist and antagonist in abandoned warehouse setting.",
    "Attended workshop on negotiation skills: practiced role-playing scenarios, learned tactics for finding win-win solutions.",
    "Excerpt from city recycling bulletin: curbside compost pickup shifts to Fridays starting next month; add reminder to bin calendar.",
    "Saved from barista forum: pour-over recipe calls for 15g coffee to 250g water with 45-second bloom; testing on weekend brew bar.",
    "Quote from leadership book *The Culture Map*: \"Listen for what isn't said in low-context teams\"; include in Friday lunch-and-learn notes.",
    "Spec sheet snippet: espresso machine requires 20-amp dedicated circuit and 6\" rear clearance; confirm café electrical plan before install.",
    "Travel guide note: Louvre extended hours on Wednesdays and Fridays until 9:45 p.m.; schedule night visit for photography practice."
]

for i, note in enumerate(validation_examples, 1):
    # Get classification
    pred = model.predict(tf.constant([note]), verbose=0)
    pred_class = int(np.argmax(pred[0]))
    predicted_category = NOTE_TYPES[pred_class]
    
    # Generate enhanced summary
    enhanced_summary = generate_enhanced_summary(note, predicted_category)
    
    print(f"\nValidation Example {i}:")
    print(f"  Raw Input: {note[:100]}{'...' if len(note) > 100 else ''}")
    print(f"  Classified As: {predicted_category}")
    print(f"  Enhanced Summary: {enhanced_summary}")

print("\n" + "="*80)
print("ENHANCED SUMMARY VALIDATION COMPLETE")
print("="*80 + "\n")

# Step 5: Export to TFLite
print("\n[5/5] Exporting to TFLite...")

# Define output directory (always save to project directory)
OUTPUT_DIR = SCRIPT_DIR
print(f"Output directory: {OUTPUT_DIR}")

# Save Keras model and exported SavedModel for conversion
keras_path = OUTPUT_DIR / 'note_classifier_final.keras'
model.save(str(keras_path))
print(f"Saved: {keras_path}")

saved_model_dir = OUTPUT_DIR / 'note_classifier_saved_model'
if saved_model_dir.exists():
    shutil.rmtree(saved_model_dir)

tf.saved_model.save(model, str(saved_model_dir))
print(f"Saved: {saved_model_dir}/ (SavedModel)")

# Convert to TFLite from the SavedModel representation - requires SELECT_TF_OPS
converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))

# Enable optimizations for smaller model size
converter.optimizations = [tf.lite.Optimize.DEFAULT]

# Use the legacy converter to maintain operator compatibility with older runtimes
converter.experimental_new_converter = False

# CRITICAL: Support SELECT_TF_OPS for text processing operations
# TextVectorization uses StringLower, StaticRegexReplace, StringSplitV2, RaggedTensorToTensor
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,  # Standard TFLite ops
    tf.lite.OpsSet.SELECT_TF_OPS      # Required for text processing
]

# Keep float32 precision
converter.target_spec.supported_types = [tf.float32]

# Preserve tensor list operations for text processing
converter._experimental_lower_tensor_list_ops = False

# Convert model
tflite_model = converter.convert()

# Always attempt to downgrade any FULLY_CONNECTED ops that exceed the
# interpreter version supported by the Android runtime. This guarantees that
# the exported artifact is compatible even if the converter adds newer
# operator versions.
tflite_model, downgraded = _downgrade_fully_connected_ops(tflite_model)
if downgraded:
    print(
        "Detected FULLY_CONNECTED op version > 11. Downgraded for legacy "
        "interpreter compatibility."
    )

# Validate operator compatibility with the on-device runtime and summarise the
# exported buffer, ensuring the downgrade helper actually took effect.
_describe_model_compatibility(tflite_model, label="Exported model")


try:
    tf.lite.Interpreter(model_content=tflite_model)
except Exception as exc:  # pragma: no cover - defensive verification
    raise RuntimeError(
        "Generated model could not be loaded by TensorFlow Lite. "
        "Ensure the conversion environment matches the Android runtime."
    ) from exc

tflite_path = OUTPUT_DIR / 'note_classifier.tflite'
with open(tflite_path, 'wb') as f:
    f.write(tflite_model)

size_mb = len(tflite_model)/(1024*1024)
print(f"Saved: {tflite_path} ({size_mb:.2f} MB)")
print(f"Note: Model requires SELECT_TF_OPS runtime (text processing operations)")

if ASSETS_DIR != OUTPUT_DIR:
    dest_path = ASSETS_DIR / 'note_classifier.tflite'
    shutil.copy2(tflite_path, dest_path)
    print(f"Copied: {dest_path}")

shutil.rmtree(saved_model_dir)
print(f"Cleaned: {saved_model_dir}/")

# Create deployment files
mapping_path = OUTPUT_DIR / 'category_mapping.json'
with open(mapping_path, 'w') as f:
    json.dump({"categories": NOTE_TYPES}, f, indent=2)

metadata_path = OUTPUT_DIR / 'deployment_metadata.json'
with open(metadata_path, 'w') as f:
    json.dump({
        "created": datetime.now().isoformat(),
        "validation_accuracy": f"{best_acc*100:.2f}%",
        "test_accuracy": f"{test_acc:.1f}%",
        "categories": NOTE_TYPES,
        "model_size_mb": f"{size_mb:.2f}"
    }, f, indent=2)

if ASSETS_DIR != OUTPUT_DIR:
    mapping_dest = ASSETS_DIR / 'category_mapping.json'
    shutil.copy2(mapping_path, mapping_dest)
    print(f"Copied: {mapping_dest}")

    metadata_dest = ASSETS_DIR / 'deployment_metadata.json'
    shutil.copy2(metadata_path, metadata_dest)
    print(f"Copied: {metadata_dest}")

readme = f"""# Note Classifier - Deployment Package

## Performance
- Validation Accuracy: {best_acc*100:.2f}%
- Test Accuracy: {test_acc:.1f}%
- Model Size: {size_mb:.2f} MB

## Files␊
1. note_classifier.tflite - TFLite model␊
2. category_mapping.json - Category names␊
3. deployment_metadata.json - Training results␊
4. note_classifier_final.keras - Optional Keras checkpoint for reference (not bundled in app)

## Android Integration

```kotlin
val interpreter = Interpreter(loadModelFile("note_classifier.tflite"))
val input = arrayOf(noteText)
val output = Array(1) {{ FloatArray({NUM_CATEGORIES}) }}
interpreter.run(input, output)
val categoryIndex = output[0].indices.maxByOrNull {{ output[0][it] }} ?: 0
val category = categories[categoryIndex]
```

Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
"""

readme_path = OUTPUT_DIR / 'DEPLOYMENT_README.md'
with open(readme_path, 'w', encoding='utf-8') as f:
    f.write(readme)

print(f"Saved: {mapping_path}")
print(f"Saved: {metadata_path}")
print(f"Saved: {readme_path}")

print("\n" + "="*80)
print("DEPLOYMENT COMPLETE!")
print("="*80)
print(f"Validation: {best_acc*100:.2f}%")
print(f"Test: {test_acc:.1f}%")
print(f"TFLite: note_classifier.tflite ({size_mb:.2f} MB)")
print("Ready for Android deployment!")
print("="*80)
