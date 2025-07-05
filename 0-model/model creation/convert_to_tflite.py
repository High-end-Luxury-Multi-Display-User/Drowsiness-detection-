from tensorflow import keras
import tensorflow as tf

MODEL_PATH = "model.keras"
TFLITE_MODEL_PATH = "model.tflite"

# Load the trained model
model = keras.models.load_model(MODEL_PATH)
print("✅ Loaded .keras model.")

# Convert to TensorFlow Lite
converter = tf.lite.TFLiteConverter.from_keras_model(model)

# (Optional) Enable optimizations (e.g., quantization)
# Uncomment below to reduce size but might reduce accuracy:
# converter.optimizations = [tf.lite.Optimize.DEFAULT]

# Convert the model
tflite_model = converter.convert()
print("✅ Conversion done.")

# Save the .tflite model
with open(TFLITE_MODEL_PATH, "wb") as f:
    f.write(tflite_model)
print(f"✅ Saved TFLite model to {TFLITE_MODEL_PATH}")

