This application is a fully featured note taking applicaiton with checklist and reminder functionality. It should wrok 100% offline and provide encryption for user selected notes and archived note collections.

The application uses a trained tflite model to summarise note contents using AI (contained in the assets directory and named "note_classifier.tflite"). The model is generated offline and uploaded to the assets directory. The code for building this model can be found in complete_pipeline.py and the training data is contained in training_data_large.py. These files are contained in the assets/scripts directory. The model is bundled with the app apk to ensure that it functions offline.

Category mapping information can be found in category_mapping.json (in the same folder as the .tflite file).
DEPLOYMENT_README.md and DEPLOYMENT_INSTRUCTIONS.txt contain some useful Android deployment information.

Note that complete_pipeline.py contains validation at the end of the script that outputs an "Enhanced summary". This is the form that the summarised note should take, and the same structure should be implemented in the application.

During Codex development, when running gradlew, run it with "--console=plain" option as Gradle's large output causes the session to close.
