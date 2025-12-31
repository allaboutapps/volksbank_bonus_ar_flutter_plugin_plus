package tech.graaf.franz.ar_flutter_plugin_plus

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.deserializeMatrix4

// Responsible for creating Renderables and Nodes
class ArModelBuilder {
    
    private val TAG = "ArModelBuilder"

    // Creates feature point node
    suspend fun makeFeaturePointNode(
        context: Context,
        arSceneView: ARSceneView,
        xPos: Float,
        yPos: Float,
        zPos: Float
    ): Node? {
        return try {
            val featurePoint = Node(arSceneView.engine)
            featurePoint.position = Position(xPos, yPos, zPos)
            featurePoint.scale = Scale(0.01f, 0.01f, 0.01f)
            featurePoint
        } catch (e: Exception) {
            Log.e(TAG, "Error creating feature point: ${e.message}")
            null
        }
    }

    // Creates a coordinate system model at the world origin (X-axis: red, Y-axis: green, Z-axis: blue)
    suspend fun makeWorldOriginNode(context: Context, arSceneView: ARSceneView): Node? {
        return try {
        val axisSize = 0.1f
            val rootNode = Node(arSceneView.engine)
            
            // X-axis (red)
            val xNode = Node(arSceneView.engine)
            xNode.position = Position(axisSize / 2, 0f, 0f)
            rootNode.addChildNode(xNode)
            
            // Y-axis (green)
            val yNode = Node(arSceneView.engine)
            yNode.position = Position(0f, axisSize / 2, 0f)
            rootNode.addChildNode(yNode)
            
            // Z-axis (blue)
            val zNode = Node(arSceneView.engine)
            zNode.position = Position(0f, 0f, axisSize / 2)
            rootNode.addChildNode(zNode)
            
            rootNode
        } catch (e: Exception) {
            Log.e(TAG, "Error creating world origin node: ${e.message}")
            null
        }
    }

    // Creates a node from a given gltf model path or URL
    suspend fun makeNodeFromGltf(
        context: Context,
        arSceneView: ARSceneView,
        name: String,
        modelPath: String,
        transformation: ArrayList<Double>,
        enablePans: Boolean,
        enableRotation: Boolean,
        objectManagerChannel: MethodChannel
    ): ModelNode? {
        return withContext(Dispatchers.Main) {
            try {
                // Load the model instance using the model loader
                val modelInstance: ModelInstance? = arSceneView.modelLoader.loadModelInstance(modelPath)
                
                if (modelInstance != null) {
                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        autoAnimate = true,
                        scaleToUnits = null,
                        centerOrigin = null
                    )
                    
                    modelNode.name = name
                    
                    // Apply transformation
                    val transform = deserializeMatrix4(transformation)
                    modelNode.scale = Scale(transform.first.x, transform.first.y, transform.first.z)
                    modelNode.position = Position(transform.second.x, transform.second.y, transform.second.z)
                    modelNode.quaternion = Quaternion(transform.third.x, transform.third.y, transform.third.z, transform.third.w)
                    
                    // Set up gesture handling if enabled
                    if (enablePans || enableRotation) {
                        setupGestureHandling(modelNode, objectManagerChannel, enablePans, enableRotation)
                    }
                    
                    modelNode
                } else {
                    Log.e(TAG, "Failed to load model instance from: $modelPath")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading GLTF model: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    // Creates a node from a given glb model path or URL
    suspend fun makeNodeFromGlb(
        context: Context,
        arSceneView: ARSceneView,
        name: String,
        modelPath: String,
        transformation: ArrayList<Double>,
        enablePans: Boolean,
        enableRotation: Boolean,
        objectManagerChannel: MethodChannel
    ): ModelNode? {
        return withContext(Dispatchers.Main) {
            try {
                // Load the model instance using the model loader
                val modelInstance: ModelInstance? = arSceneView.modelLoader.loadModelInstance(modelPath)
                
                if (modelInstance != null) {
                    val modelNode = ModelNode(
                        modelInstance = modelInstance,
                        autoAnimate = true,
                        scaleToUnits = null,
                        centerOrigin = null
                    )
                    
                    modelNode.name = name
                    
                    // Apply transformation
                    val transform = deserializeMatrix4(transformation)
                    modelNode.scale = Scale(transform.first.x, transform.first.y, transform.first.z)
                    modelNode.position = Position(transform.second.x, transform.second.y, transform.second.z)
                    modelNode.quaternion = Quaternion(transform.third.x, transform.third.y, transform.third.z, transform.third.w)
                    
                    // Set up gesture handling if enabled
                    if (enablePans || enableRotation) {
                        setupGestureHandling(modelNode, objectManagerChannel, enablePans, enableRotation)
                    }
                    
                    modelNode
                } else {
                    Log.e(TAG, "Failed to load model instance from: $modelPath")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading GLB model: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }
    
    private fun setupGestureHandling(
        modelNode: ModelNode,
        objectManagerChannel: MethodChannel,
        enablePans: Boolean,
        enableRotation: Boolean
    ) {
        // SceneView 2.0 uses different gesture handling
        // The gestures are typically handled at the ARSceneView level
        // For now, we mark the node as editable
        modelNode.isEditable = true
        
        // Note: In SceneView 2.0, gesture callbacks would be set up differently
        // through the ARSceneView's gesture handling system
        modelNode.onEditingChanged = { editingTransforms ->
            if (editingTransforms.isEmpty()) {
                // Editing ended
                val transformData = HashMap<String, Any>()
                transformData["name"] = modelNode.name ?: ""
                
                val position = modelNode.position
                val rotation = modelNode.quaternion
                val scale = modelNode.scale
                
                // Create transformation matrix
                val transform = createTransformationMatrix(position, rotation, scale)
                transformData["transform"] = transform
                
                objectManagerChannel.invokeMethod("onPanEnd", transformData)
            }
        }
    }
    
    private fun createTransformationMatrix(
        position: Float3,
        rotation: Quaternion,
        scale: Float3
    ): DoubleArray {
        // Create a 4x4 transformation matrix from position, rotation, and scale
        val matrix = DoubleArray(16)
        
        // Convert quaternion to rotation matrix and combine with scale
        val x = rotation.x
        val y = rotation.y
        val z = rotation.z
        val w = rotation.w
        
        val x2 = x + x
        val y2 = y + y
        val z2 = z + z
        val xx = x * x2
        val xy = x * y2
        val xz = x * z2
        val yy = y * y2
        val yz = y * z2
        val zz = z * z2
        val wx = w * x2
        val wy = w * y2
        val wz = w * z2
        
        matrix[0] = ((1 - (yy + zz)) * scale.x).toDouble()
        matrix[1] = ((xy + wz) * scale.x).toDouble()
        matrix[2] = ((xz - wy) * scale.x).toDouble()
        matrix[3] = 0.0
        
        matrix[4] = ((xy - wz) * scale.y).toDouble()
        matrix[5] = ((1 - (xx + zz)) * scale.y).toDouble()
        matrix[6] = ((yz + wx) * scale.y).toDouble()
        matrix[7] = 0.0
        
        matrix[8] = ((xz + wy) * scale.z).toDouble()
        matrix[9] = ((yz - wx) * scale.z).toDouble()
        matrix[10] = ((1 - (xx + yy)) * scale.z).toDouble()
        matrix[11] = 0.0
        
        matrix[12] = position.x.toDouble()
        matrix[13] = position.y.toDouble()
        matrix[14] = position.z.toDouble()
        matrix[15] = 1.0
        
        return matrix
     }
}
