This application use a trained tflite model to summarise note contents using AI (contained in the assets directory and named "note_classifier.tflite"). The code for the building of this model can be found in build_tensor.py and the training data is contained in training_data.py. This model is bundled with the app apk to ensure that it functions offline.

build_tenosr.py contains validation at the end of the script that outputs an "Enhanced summary". This is the form that the summarised note should take, and the same structure should be implemented in the application.
