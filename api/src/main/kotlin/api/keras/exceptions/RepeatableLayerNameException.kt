package api.keras.exceptions

/**
 * Thrown by [api.keras.Sequential] model during model initialization if the model layers has the same name in a few layers.
 */
class RepeatableLayerNameException(layerName: String) :
    Exception("The layer name $layerName is used in previous layers. The layer name should be unique.")
