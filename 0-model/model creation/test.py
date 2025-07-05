import cv2
import numpy as np
import tensorflow as tf
from tensorflow.keras.applications.efficientnet import preprocess_input

# === CONFIGURATION ===
TFLITE_MODEL_PATH = "model.tflite"
IMAGE_SIZE = (150, 150)

# === Load TFLite Model ===
interpreter = tf.lite.Interpreter(model_path=TFLITE_MODEL_PATH)
interpreter.allocate_tensors()

# Get input and output details
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print("✅ TFLite model loaded.")

# === Initialize Webcam ===
cap = cv2.VideoCapture(0)
if not cap.isOpened():
    print("❌ Failed to open webcam.")
    exit(1)

# Load Haar cascade for eye detection
eye_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_eye.xml")
print("✅ Eye cascade loaded. Press 'q' to quit.")

while True:
    ret, frame = cap.read()
    if not ret:
        print("❌ Failed to read frame.")
        break

    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    eyes = eye_cascade.detectMultiScale(gray, 1.3, 5)

    for (x, y, w, h) in eyes:
        eye_roi = frame[y:y+h, x:x+w]
        resized = cv2.resize(eye_roi, IMAGE_SIZE)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
        preprocessed = preprocess_input(rgb)
        input_data = np.expand_dims(preprocessed, axis=0).astype(np.float32)

        # Run inference
        interpreter.set_tensor(input_details[0]['index'], input_data)
        interpreter.invoke()
        pred = interpreter.get_tensor(output_details[0]['index'])

        prob = pred[0][0]
        status = "Open" if prob > 0.5 else "Closed"
        prob_text = f"{prob:.2f}"

        # Draw rectangle and label
        color = (0, 255, 0) if status == "Open" else (0, 0, 255)
        cv2.rectangle(frame, (x, y), (x+w, y+h), color, 2)
        cv2.putText(
            frame,
            f"{status} ({prob_text})",
            (x, y - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            color,
            2,
        )

    cv2.imshow("Eye State Detection (TFLite)", frame)

    if cv2.waitKey(1) & 0xFF == ord("q"):
        break

cap.release()
cv2.destroyAllWindows()

