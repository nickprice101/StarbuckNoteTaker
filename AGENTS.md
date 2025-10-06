This application is a fully featured note taking applicaiton with checklist and reminder functionality. It should wrok 100% offline and provide encryption for user selected notes and archived note collections.

The application uses a trained tflite model to summarise note contents using AI (contained in the assets directory and named "note_classifier.tflite"). The code for the building of this model can be found in build_tensor.py and the training data is contained in training_data.py. This model is bundled with the app apk to ensure that it functions offline.

build_tenosr.py contains validation at the end of the script that outputs an "Enhanced summary". This is the form that the summarised note should take, and the same structure should be implemented in the application.
