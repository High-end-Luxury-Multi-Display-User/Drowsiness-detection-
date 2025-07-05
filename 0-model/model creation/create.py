from tensorflow import keras

# 1. Rebuild the model (or load it from JSON and weights)
# Example if you have model_from_json and .h5:
with open("config.json", "r") as f:
    model_json = f.read()
model = keras.models.model_from_json(model_json)
model.load_weights("model.weights.h5")

# 2. Save as .keras file
model.save("model.keras")

