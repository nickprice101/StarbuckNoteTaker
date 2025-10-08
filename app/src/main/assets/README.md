AI sumamry model files.

note_classifier.tflite
The actual TensorFlow Lite model
This is what the Android app loads

category_mapping.json
Maps model output indices to category names
The app needs this to interpret predictions

deployment_metadata.json
Contains model version info and accuracy metrics
Useful for tracking the model version we're using
