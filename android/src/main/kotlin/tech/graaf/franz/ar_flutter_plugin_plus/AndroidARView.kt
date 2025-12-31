package tech.graaf.franz.ar_flutter_plugin_plus

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion as MathQuaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.deserializeMatrix4
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializeAnchor
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializeHitResult
import tech.graaf.franz.ar_flutter_plugin_plus.Serialization.serializePose
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.FloatBuffer

internal class AndroidARView(
        val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int,
        creationParams: Map<String?, Any?>?
) : PlatformView {
    // constants
    private val TAG: String = AndroidARView::class.java.name
    
    // Lifecycle variables
    private var mUserRequestedInstall = true
    lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val viewContext: Context
    
    // Platform channels
    private val sessionManagerChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")
    
    // UI variables
    private lateinit var arSceneView: ARSceneView
    private var showFeaturePoints = false
    private var showAnimatedGuide = false
    private var pointCloudNodes = mutableListOf<Node>()
    private var worldOriginNode: Node? = null
    
    // Setting defaults
    private var enableRotation = false
    private var enablePans = false
    
    // Model builder
    private var modelBuilder = ArModelBuilder()
    
    // Cloud anchor handler
    private lateinit var cloudAnchorHandler: CloudAnchorHandler

    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Store nodes by name for lookup
    private val nodesByName = mutableMapOf<String, Node>()
    private val anchorNodesByName = mutableMapOf<String, AnchorNode>()
    
    // Store current frame for callbacks
    private var currentFrame: Frame? = null

    // Method channel handlers
    private val onSessionMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                Log.d(TAG, "AndroidARView onsessionmethodcall received a call!")
                    when (call.method) {
                        "init" -> {
                            initializeARView(call, result)
                        }
                        "getAnchorPose" -> {
                        val anchorId = call.argument<String>("anchorId")
                        val anchorNode = anchorNodesByName[anchorId]
                        if (anchorNode != null && anchorNode.anchor != null) {
                                result.success(serializePose(anchorNode.anchor!!.pose))
                            } else {
                                result.error("Error", "could not get anchor pose", null)
                            }
                        }
                        "getCameraPose" -> {
                        val frame = currentFrame
                        val cameraPose = frame?.camera?.displayOrientedPose
                            if (cameraPose != null) {
                            result.success(serializePose(cameraPose))
                            } else {
                                result.error("Error", "could not get camera pose", null)
                            }
                        }
                        "snapshot" -> {
                        takeSnapshot(result)
                        }
                        "dispose" -> {
                            dispose()
                        }
                        else -> {}
                    }
                }
            }
    
    private val onObjectMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                Log.d(TAG, "AndroidARView onobjectmethodcall received a call!")
                    when (call.method) {
                        "init" -> {
                        // Initialization if needed
                        }
                        "addNode" -> {
                        val dictNode: HashMap<String, Any>? = call.arguments as? HashMap<String, Any>
                        dictNode?.let {
                            coroutineScope.launch {
                                try {
                                    val success = addNode(it)
                                    result.success(success)
                                } catch (e: Exception) {
                                    result.error("e", e.message, e.stackTrace.toString())
                                }
                                }
                            }
                        }
                        "addNodeToPlaneAnchor" -> {
                        val dictNode: HashMap<String, Any>? = call.argument<HashMap<String, Any>>("node")
                        val dictAnchor: HashMap<String, Any>? = call.argument<HashMap<String, Any>>("anchor")
                        if (dictNode != null && dictAnchor != null) {
                            coroutineScope.launch {
                                try {
                                    val success = addNode(dictNode, dictAnchor)
                                    result.success(success)
                                } catch (e: Exception) {
                                    result.error("e", e.message, e.stackTrace.toString())
                                }
                                }
                            } else {
                                result.success(false)
                            }
                        }
                        "removeNode" -> {
                            val nodeName: String? = call.argument<String>("name")
                        nodeName?.let {
                            val node = nodesByName.remove(nodeName)
                            node?.let {
                                it.parent = null
                                    result.success(null)
                                }
                            }
                        }
                        "transformationChanged" -> {
                            val nodeName: String? = call.argument<String>("name")
                            val newTransformation: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")
                        nodeName?.let { name ->
                            newTransformation?.let { transform ->
                                    transformNode(name, transform)
                                    result.success(null)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
    
    private val onAnchorMethodCall =
            object : MethodChannel.MethodCallHandler {
                override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
                    when (call.method) {
                        "addAnchor" -> {
                            val anchorType: Int? = call.argument<Int>("type")
                        if (anchorType != null) {
                            when (anchorType) {
                                    0 -> { // Plane Anchor
                                        val transform: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")
                                        val name: String? = call.argument<String>("name")
                                    if (name != null && transform != null) {
                                            result.success(addPlaneAnchor(transform, name))
                                        } else {
                                            result.success(false)
                                        }
                                    }
                                    else -> result.success(false)
                                }
                            } else {
                                result.success(false)
                            }
                        }
                        "removeAnchor" -> {
                            val anchorName: String? = call.argument<String>("name")
                        anchorName?.let { name ->
                                removeAnchor(name)
                            }
                        }
                        "initGoogleCloudAnchorMode" -> {
                        val session = arSceneView.session
                        if (session != null) {
                            val config = Config(session)
                                config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                                config.focusMode = Config.FocusMode.AUTO
                            session.configure(config)

                            cloudAnchorHandler = CloudAnchorHandler(session)
                            } else {
                                sessionManagerChannel.invokeMethod("onError", listOf("Error initializing cloud anchor mode: Session is null"))
                            }
                        }
                    "uploadAnchor" -> {
                            val anchorName: String? = call.argument<String>("name")
                            val ttl: Int? = call.argument<Int>("ttl")
                            anchorName?.let {
                            val anchorNode = anchorNodesByName[anchorName]
                            if (anchorNode?.anchor != null) {
                                if (ttl != null) {
                                    cloudAnchorHandler.hostCloudAnchorWithTtl(anchorName, anchorNode.anchor, cloudAnchorUploadedListener(), ttl)
                                } else {
                                    cloudAnchorHandler.hostCloudAnchor(anchorName, anchorNode.anchor, cloudAnchorUploadedListener())
                                }
                                result.success(true)
                            } else {
                                result.success(false)
                            }
                        }
                        }
                        "downloadAnchor" -> {
                            val anchorId: String? = call.argument<String>("cloudanchorid")
                            anchorId?.let {
                                cloudAnchorHandler.resolveCloudAnchor(anchorId, cloudAnchorDownloadedListener())
                            }
                        }
                        else -> {}
                    }
                }
            }

    override fun getView(): View {
        return arSceneView
    }

    override fun dispose() {
        Log.d(TAG, "dispose called")
        try {
            coroutineScope.cancel()
            arSceneView.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    init {
        Log.d(TAG, "Initializing AndroidARView")
        viewContext = context

        arSceneView = ARSceneView(context)

        setupLifeCycle(context)

        sessionManagerChannel.setMethodCallHandler(onSessionMethodCall)
        objectManagerChannel.setMethodCallHandler(onObjectMethodCall)
        anchorManagerChannel.setMethodCallHandler(onAnchorMethodCall)

        onResume()
    }

    private fun setupLifeCycle(context: Context) {
        activityLifecycleCallbacks =
                object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        Log.d(TAG, "onActivityCreated")
                    }

                    override fun onActivityStarted(activity: Activity) {
                        Log.d(TAG, "onActivityStarted")
                    }

                    override fun onActivityResumed(activity: Activity) {
                        Log.d(TAG, "onActivityResumed")
                        onResume()
                    }

                    override fun onActivityPaused(activity: Activity) {
                        Log.d(TAG, "onActivityPaused")
                        onPause()
                    }

                    override fun onActivityStopped(activity: Activity) {
                        Log.d(TAG, "onActivityStopped")
                        onPause()
                    }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                    override fun onActivityDestroyed(activity: Activity) {
                        Log.d(TAG, "onActivityDestroyed")
                    }
                }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
    }

    fun onResume() {
        // SceneView handles session creation internally
        // Check if ARCore is available and installed
            try {
                if (ArCoreApk.getInstance().requestInstall(activity, mUserRequestedInstall) ==
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    Log.d(TAG, "Install of ArCore APK requested")
                    mUserRequestedInstall = false
                    return
            }
            } catch (ex: UnavailableUserDeclinedInstallationException) {
            Toast.makeText(activity, "ARCore installation declined", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableArcoreNotInstalledException) {
                Toast.makeText(activity, "Please install ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableApkTooOldException) {
                Toast.makeText(activity, "Please update ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableSdkTooOldException) {
                Toast.makeText(activity, "Please update this app", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableDeviceNotCompatibleException) {
            Toast.makeText(activity, "This device does not support AR", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to create AR session", Toast.LENGTH_LONG).show()
            return
        }
    }

    fun onPause() {
        // SceneView handles pause internally
    }

    private fun takeSnapshot(result: MethodChannel.Result) {
        val bitmap = Bitmap.createBitmap(arSceneView.width, arSceneView.height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread("PixelCopier")
        handlerThread.start()
        
        PixelCopy.request(arSceneView, bitmap, { copyResult: Int ->
            Log.d(TAG, "PIXELCOPY DONE")
            if (copyResult == PixelCopy.SUCCESS) {
                try {
                    val mainHandler = Handler(viewContext.mainLooper)
                    mainHandler.post {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                        val data = stream.toByteArray()
                        result.success(data)
                    }
                } catch (e: IOException) {
                    result.error("e", e.message, e.stackTrace.toString())
                }
            } else {
                result.error("e", "failed to take screenshot", null)
            }
            handlerThread.quitSafely()
        }, Handler(handlerThread.looper))
    }

    private fun initializeARView(call: MethodCall, result: MethodChannel.Result) {
        // Unpack call arguments
        val argShowFeaturePoints: Boolean? = call.argument<Boolean>("showFeaturePoints")
        val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
        val argShowPlanes: Boolean? = call.argument<Boolean>("showPlanes")
        val argCustomPlaneTexturePath: String? = call.argument<String>("customPlaneTexturePath")
        val argShowWorldOrigin: Boolean? = call.argument<Boolean>("showWorldOrigin")
        val argHandleTaps: Boolean? = call.argument<Boolean>("handleTaps")
        val argHandleRotation: Boolean? = call.argument<Boolean>("handleRotation")
        val argHandlePans: Boolean? = call.argument<Boolean>("handlePans")
        val argShowAnimatedGuide: Boolean? = call.argument<Boolean>("showAnimatedGuide")
        val argTrackingImagePaths: List<String>? = call.argument<List<String>>("trackingImagePaths")

        // Configure feature points
        showFeaturePoints = argShowFeaturePoints == true

        // Set up frame update listener
        arSceneView.onSessionUpdated = { session, frame ->
            currentFrame = frame
            onFrame(frame)
        }

        // Configure tap handling  
        if (argHandleTaps == true) {
            arSceneView.setOnTouchListener { _, motionEvent ->
                onTap(motionEvent)
            }
        }

        // Configure gestures
        enableRotation = argHandleRotation == true
        enablePans = argHandlePans == true

        // Configure plane detection
        arSceneView.configureSession { session, config ->
        when (argPlaneDetectionConfig) {
                1 -> config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                2 -> config.planeFindingMode = Config.PlaneFindingMode.VERTICAL
                3 -> config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                else -> config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
            
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO

        // Configure image tracking
        argTrackingImagePaths?.let { imagePaths ->
                setupImageTracking(session, config, imagePaths)
            }
        }

        // Configure whether or not detected planes should be shown
        arSceneView.planeRenderer.isVisible = argShowPlanes == true

        // Configure world origin
        if (argShowWorldOrigin == true) {
            coroutineScope.launch {
                worldOriginNode = modelBuilder.makeWorldOriginNode(viewContext, arSceneView)
                worldOriginNode?.let { arSceneView.addChildNode(it) }
            }
        }

        result.success(null)
    }

    private fun onFrame(frame: Frame) {
        // Handle feature points
        if (showFeaturePoints) {
            // Clear old points
            pointCloudNodes.forEach { it.parent = null }
            pointCloudNodes.clear()
            
            val pointCloud = frame.acquirePointCloud()
            val points = pointCloud.points
            
            if (points.limit() / 4 >= 1) {
                for (index in 0 until points.limit() / 4) {
                    coroutineScope.launch {
                        val featurePoint = modelBuilder.makeFeaturePointNode(
                                    viewContext,
                            arSceneView,
                                    points.get(4 * index),
                                    points.get(4 * index + 1),
                            points.get(4 * index + 2)
                        )
                        featurePoint?.let {
                            arSceneView.addChildNode(it)
                            pointCloudNodes.add(it)
                        }
                    }
                }
            }
            pointCloud.release()
        }

        // Check for cloud anchor updates
        val updatedAnchors = frame.updatedAnchors
        if (this::cloudAnchorHandler.isInitialized) {
            cloudAnchorHandler.onUpdate(updatedAnchors)
        }

        // Check for image tracking
        checkForTrackedImages(frame)
    }

    private suspend fun addNode(dictNode: HashMap<String, Any>, dictAnchor: HashMap<String, Any>? = null): Boolean {
        return try {
            val nodeType = dictNode["type"] as Int
            val nodeName = dictNode["name"] as String
            val modelUri = dictNode["uri"] as String
            val transformation = dictNode["transformation"] as ArrayList<Double>
            
            val node = when (nodeType) {
                0 -> { // GLTF2 Model from Flutter asset folder
                    val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
                    val key: String = loader.getLookupKeyForAsset(modelUri)
                    modelBuilder.makeNodeFromGltf(viewContext, arSceneView, nodeName, key, transformation, enablePans, enableRotation, objectManagerChannel)
                }
                1 -> { // GLB Model from Flutter asset folder
                    val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
                    val key: String = loader.getLookupKeyForAsset(modelUri)
                    modelBuilder.makeNodeFromGlb(viewContext, arSceneView, nodeName, key, transformation, enablePans, enableRotation, objectManagerChannel)
                }
                2 -> { // GLB Model from the web
                    modelBuilder.makeNodeFromGlb(viewContext, arSceneView, nodeName, modelUri, transformation, enablePans, enableRotation, objectManagerChannel)
                }
                3 -> { // fileSystemAppFolderGLB
                    val documentsPath = viewContext.applicationInfo.dataDir
                    val assetPath = "$documentsPath/app_flutter/$modelUri"
                    modelBuilder.makeNodeFromGlb(viewContext, arSceneView, nodeName, assetPath, transformation, enablePans, enableRotation, objectManagerChannel)
                }
                4 -> { // fileSystemAppFolderGLTF2
                    val documentsPath = viewContext.applicationInfo.dataDir
                    val assetPath = "$documentsPath/app_flutter/$modelUri"
                    modelBuilder.makeNodeFromGltf(viewContext, arSceneView, nodeName, assetPath, transformation, enablePans, enableRotation, objectManagerChannel)
                }
                else -> null
            }

            if (node != null) {
                nodesByName[nodeName] = node
                
                val anchorName: String? = dictAnchor?.get("name") as? String
                val anchorType: Int? = dictAnchor?.get("type") as? Int
                
                                if (anchorName != null && anchorType != null) {
                    val anchorNode = anchorNodesByName[anchorName]
                                    if (anchorNode != null) {
                        anchorNode.addChildNode(node)
                        true
                                    } else {
                        false
                                    }
                                } else {
                    arSceneView.addChildNode(node)
                    true
                }
                                    } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding node: ${e.message}")
                                val mainHandler = Handler(viewContext.mainLooper)
            mainHandler.post {
                sessionManagerChannel.invokeMethod("onError", listOf("Unable to load renderable: ${e.message}"))
            }
            false
        }
    }

    private fun transformNode(name: String, transform: ArrayList<Double>) {
        val node = nodesByName[name]
        node?.let {
            val transformTriple = deserializeMatrix4(transform)
            it.scale = Scale(transformTriple.first.x, transformTriple.first.y, transformTriple.first.z)
            it.position = Position(transformTriple.second.x, transformTriple.second.y, transformTriple.second.z)
            it.quaternion = MathQuaternion(transformTriple.third.x, transformTriple.third.y, transformTriple.third.z, transformTriple.third.w)
        }
    }

    private fun onTap(motionEvent: MotionEvent): Boolean {
        val frame = currentFrame
        
        if (motionEvent.action == MotionEvent.ACTION_DOWN) {
            // Handle plane/point tap using ARCore hit test
                val allHitResults = frame?.hitTest(motionEvent) ?: listOf<HitResult>()
            val planeAndPointHitResults = allHitResults.filter { 
                (it.trackable is Plane) || (it.trackable is Point) 
            }
                val serializedPlaneAndPointHitResults: ArrayList<HashMap<String, Any>> =
                    ArrayList(planeAndPointHitResults.map { serializeHitResult(it) })
            sessionManagerChannel.invokeMethod("onPlaneOrPointTap", serializedPlaneAndPointHitResults)
                return true
        }
        return false
    }

    private fun addPlaneAnchor(transform: ArrayList<Double>, name: String): Boolean {
        return try {
            val transformTriple = deserializeMatrix4(transform)
            val position = floatArrayOf(transformTriple.second.x, transformTriple.second.y, transformTriple.second.z)
            val rotation = floatArrayOf(transformTriple.third.x, transformTriple.third.y, transformTriple.third.z, transformTriple.third.w)
            val pose = Pose(position, rotation)
            
            val session = arSceneView.session
            val anchor: Anchor? = session?.createAnchor(pose)
            if (anchor != null) {
                val anchorNode = AnchorNode(arSceneView.engine, anchor)
            anchorNode.name = name
                arSceneView.addChildNode(anchorNode)
                anchorNodesByName[name] = anchorNode
            true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating anchor: ${e.message}")
            false
        }
    }

    private fun removeAnchor(name: String) {
        val anchorNode = anchorNodesByName.remove(name)
        anchorNode?.let {
            // Remove corresponding anchor from tracking
            anchorNode.anchor?.detach()
            // Remove children
            for (child in anchorNode.childNodes.toList()) {
                nodesByName.remove(child.name)
                child.parent = null
            }
            // Remove anchor node
            anchorNode.parent = null
        }
    }

    private inner class cloudAnchorUploadedListener : CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(TAG, "Error uploading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error uploading anchor, state $cloudState"))
                return
            }
            
            // Swap old and new anchor of the respective AnchorNode
            val anchorNode = anchorNodesByName[anchorName]
            val oldAnchor = anchorNode?.anchor
            if (anchorNode != null) {
                // Create new anchor node with the cloud anchor
                val newAnchorNode = AnchorNode(arSceneView.engine, anchor)
                newAnchorNode.name = anchorName
                
                // Transfer children
                for (child in anchorNode.childNodes.toList()) {
                    child.parent = null
                    newAnchorNode.addChildNode(child)
                }
                
                // Remove old node and add new
                anchorNode.parent = null
                arSceneView.addChildNode(newAnchorNode)
                anchorNodesByName[anchorName!!] = newAnchorNode
            }
            oldAnchor?.detach()

            val args = HashMap<String, String?>()
            args["name"] = anchorName
            args["cloudanchorid"] = anchor.cloudAnchorId
            anchorManagerChannel.invokeMethod("onCloudAnchorUploaded", args)
        }
    }

    private inner class cloudAnchorDownloadedListener : CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(TAG, "Error downloading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error downloading anchor, state $cloudState"))
                return
            }
            
            val newAnchorNode = AnchorNode(arSceneView.engine, anchor)
            
            // Register new anchor on the Flutter side of the plugin
            anchorManagerChannel.invokeMethod("onAnchorDownloadSuccess", serializeAnchor(newAnchorNode, anchor), object : MethodChannel.Result {
                override fun success(resultData: Any?) {
                    newAnchorNode.name = resultData.toString()
                    arSceneView.addChildNode(newAnchorNode)
                    anchorNodesByName[resultData.toString()] = newAnchorNode
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin: $errorMessage"))
                }

                override fun notImplemented() {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin"))
                }
            })
        }
    }

    private fun checkForTrackedImages(frame: Frame) {
        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
        
        if (updatedAugmentedImages.isNotEmpty()) {
            Log.d(TAG, "Checking ${updatedAugmentedImages.size} augmented images")
        }
        
        for (augmentedImage in updatedAugmentedImages) {
            when (augmentedImage.trackingState) {
                TrackingState.TRACKING -> {
                    if (augmentedImage.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                        val imageName = augmentedImage.name ?: "unknown"
                        val centerPose = augmentedImage.centerPose
                        val transformation = serializePose(centerPose)
                        
                        val arguments = HashMap<String, Any>()
                        arguments["imageName"] = imageName
                        arguments["transformation"] = transformation
                        
                        sessionManagerChannel.invokeMethod("onImageDetected", arguments)
                        Log.d(TAG, "Image detected with full tracking: $imageName")
                    } else {
                        Log.d(TAG, "Image tracking method not full: ${augmentedImage.name} - ${augmentedImage.trackingMethod}")
                    }
                }
                TrackingState.PAUSED -> {
                    Log.d(TAG, "Image tracking paused: ${augmentedImage.name}")
                }
                TrackingState.STOPPED -> {
                    Log.d(TAG, "Image tracking stopped: ${augmentedImage.name}")
                }
            }
        }
    }

    private fun setupImageTracking(session: Session, config: Config, imagePaths: List<String>) {
        try {
            val imageDatabase = AugmentedImageDatabase(session)
            
            for (imagePath in imagePaths) {
                try {
                    val loader = FlutterInjector.instance().flutterLoader()
                    val key = loader.getLookupKeyForAsset(imagePath)
                    
                    Log.d(TAG, "🔍 Loading image - Original path: $imagePath")
                    Log.d(TAG, "🔍 Loading image - Asset key: $key")
                    
                    val inputStream = viewContext.assets.open(key)
                    Log.d(TAG, "🔍 Input stream available: ${inputStream.available()} bytes")
                    
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    
                    Log.d(TAG, "🔍 Bitmap result: ${if (bitmap != null) "SUCCESS (${bitmap.width}x${bitmap.height})" else "NULL"}")
                    
                    if (bitmap != null) {
                        val imageName = imagePath.substringAfterLast("/").substringBeforeLast(".")
                        Log.d(TAG, "Loading image: $imageName, size: ${bitmap.width}x${bitmap.height}")
                        
                        val physicalWidth = 0.1f // 10cm
                        val index = imageDatabase.addImage(imageName, bitmap, physicalWidth)
                        
                        if (index != -1) {
                            Log.d(TAG, "Successfully added image to database: $imageName (index: $index)")
                        } else {
                            Log.e(TAG, "Failed to add image to database: $imageName")
                        }
                    } else {
                        Log.e(TAG, "Failed to load bitmap for: $imagePath")
                    }
                } catch (e: Exception) {
                    when (e.javaClass.simpleName) {
                        "ImageInsufficientQualityException" -> {
                            Log.e(TAG, "❌ Image $imagePath has insufficient quality for AR tracking!")
                            sessionManagerChannel.invokeMethod("onError", listOf("Image '$imagePath' has insufficient quality for AR tracking."))
                        }
                        else -> {
                            Log.e(TAG, "Error loading image $imagePath: ${e.message}")
                        }
                    }
                    e.printStackTrace()
                }
            }
            
            config.augmentedImageDatabase = imageDatabase
            Log.d(TAG, "Image tracking configured with ${imagePaths.size} images")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up image tracking: ${e.message}")
            sessionManagerChannel.invokeMethod("onError", listOf("Error setting up image tracking: ${e.message}"))
        }
    }
}
