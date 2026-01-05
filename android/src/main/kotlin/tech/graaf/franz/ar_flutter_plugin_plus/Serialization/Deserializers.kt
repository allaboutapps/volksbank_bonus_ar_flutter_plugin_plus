package tech.graaf.franz.ar_flutter_plugin_plus.Serialization

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion

/**
 * Vector3 replacement for SceneView 2.0
 * Uses dev.romainguy.kotlin.math.Float3 instead of com.google.ar.sceneform.math.Vector3
 */
data class Vector3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun length(): Float {
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }
    
    fun toFloat3(): Float3 = Float3(x, y, z)
}

/**
 * Quaternion wrapper that's compatible with both old Sceneform and new SceneView
 */
data class QuaternionData(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f) {
    fun toQuaternion(): Quaternion = Quaternion(x, y, z, w)
}

fun deserializeMatrix4(transform: ArrayList<Double>): Triple<Vector3, Vector3, QuaternionData> {
  val scale = Vector3()
  val position = Vector3()
    val rotation: QuaternionData

  // Get the scale by calculating the length of each 3-dimensional column vector of the
  // transformation matrix
    // See https://math.stackexchange.com/questions/237369/given-this-transformation-matrix-how-do-i-decompose-it-into-translation-rotati
  scale.x = Vector3(transform[0].toFloat(), transform[1].toFloat(), transform[2].toFloat()).length()
  scale.y = Vector3(transform[4].toFloat(), transform[5].toFloat(), transform[6].toFloat()).length()
    scale.z = Vector3(transform[8].toFloat(), transform[9].toFloat(), transform[10].toFloat()).length()

  // Get the translation by taking the last column of the transformation matrix
  position.x = transform[12].toFloat()
  position.y = transform[13].toFloat()
  position.z = transform[14].toFloat()

  // Get the rotation matrix from the transformation matrix by normalizing with the scales
    val rowWiseMatrix = floatArrayOf(
          transform[0].toFloat() / scale.x,
          transform[4].toFloat() / scale.y,
          transform[8].toFloat() / scale.z,
          transform[1].toFloat() / scale.x,
          transform[5].toFloat() / scale.y,
          transform[9].toFloat() / scale.z,
          transform[2].toFloat() / scale.x,
          transform[6].toFloat() / scale.y,
        transform[10].toFloat() / scale.z
    )

  // Calculate the quaternion from the rotation matrix
    // See https://www.euclideanspace.com/maths/geometry/rotations/conversions/matrixToQuaternion/
  val trace = rowWiseMatrix[0] + rowWiseMatrix[4] + rowWiseMatrix[8]

  var w = 0.0
  var x = 0.0
  var y = 0.0
  var z = 0.0

  if (trace > 0) {
    val scalefactor = Math.sqrt(trace + 1.0) * 2
    w = 0.25 * scalefactor
    x = (rowWiseMatrix[7] - rowWiseMatrix[5]) / scalefactor
    y = (rowWiseMatrix[2] - rowWiseMatrix[6]) / scalefactor
    z = (rowWiseMatrix[3] - rowWiseMatrix[1]) / scalefactor
  } else if ((rowWiseMatrix[0] > rowWiseMatrix[4]) && (rowWiseMatrix[0] > rowWiseMatrix[8])) {
    val scalefactor = Math.sqrt(1.0 + rowWiseMatrix[0] - rowWiseMatrix[4] - rowWiseMatrix[8]) * 2
    w = (rowWiseMatrix[7] - rowWiseMatrix[5]) / scalefactor
    x = 0.25 * scalefactor
    y = (rowWiseMatrix[1] + rowWiseMatrix[3]) / scalefactor
    z = (rowWiseMatrix[2] + rowWiseMatrix[6]) / scalefactor
  } else if (rowWiseMatrix[4] > rowWiseMatrix[8]) {
    val scalefactor = Math.sqrt(1.0 + rowWiseMatrix[4] - rowWiseMatrix[0] - rowWiseMatrix[8]) * 2
    w = (rowWiseMatrix[2] - rowWiseMatrix[6]) / scalefactor
    x = (rowWiseMatrix[1] + rowWiseMatrix[3]) / scalefactor
    y = 0.25 * scalefactor
    z = (rowWiseMatrix[5] + rowWiseMatrix[7]) / scalefactor
  } else {
    val scalefactor = Math.sqrt(1.0 + rowWiseMatrix[8] - rowWiseMatrix[0] - rowWiseMatrix[4]) * 2
    w = (rowWiseMatrix[3] - rowWiseMatrix[1]) / scalefactor
    x = (rowWiseMatrix[2] + rowWiseMatrix[6]) / scalefactor
    y = (rowWiseMatrix[5] + rowWiseMatrix[7]) / scalefactor
    z = 0.25 * scalefactor
  }

    val inputRotation = QuaternionData(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

  // Rotate by an additional 180 degrees around z and y to compensate for the different model
    // coordinate system definition used in Sceneform/SceneView (in comparison to SceneKit and the definition
  // used for the Flutter API of this plugin)
    val correctionZ = QuaternionData(0.0f, 0.0f, 1.0f, 0f) // 180 degrees around Z
    val correctionY = QuaternionData(0.0f, 1.0f, 0.0f, 0f) // 180 degrees around Y

  // Calculate resulting rotation quaternion by multiplying input and corrections
    rotation = multiplyQuaternions(multiplyQuaternions(inputRotation, correctionY), correctionZ)

  return Triple(scale, position, rotation)
}

private fun multiplyQuaternions(q1: QuaternionData, q2: QuaternionData): QuaternionData {
    val w = q1.w * q2.w - q1.x * q2.x - q1.y * q2.y - q1.z * q2.z
    val x = q1.w * q2.x + q1.x * q2.w + q1.y * q2.z - q1.z * q2.y
    val y = q1.w * q2.y - q1.x * q2.z + q1.y * q2.w + q1.z * q2.x
    val z = q1.w * q2.z + q1.x * q2.y - q1.y * q2.x + q1.z * q2.w
    return QuaternionData(x, y, z, w)
}
