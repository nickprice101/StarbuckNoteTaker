# AI sumamry model files  

Contains the following files:

### note_classifier.tflite  
* The actual TensorFlow Lite model
* This is what the Android app loads

### tokenizer_vocabulary_v2.txt
* Vocabulary exported from the training TextVectorization layer
* Used by Android-side tokenization when the model expects integer token IDs

### category_mapping.json  
* Maps model output indices to category names
* The app needs this to interpret predictions

### deployment_metadata.json  
* Contains model version info and accuracy metrics
* Useful for tracking the model version we're using
