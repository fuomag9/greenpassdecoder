package com.fuomag9.greenpassdecoder

import COSE.Encrypt0Message
import COSE.Message
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues.TAG
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.fuomag9.greenpassdecoder.MainActivity.Companion.ShowResults
import com.fuomag9.greenpassdecoder.MainActivity.Companion.decodedPass
import com.fuomag9.greenpassdecoder.MainActivity.Companion.imageUri
import com.fuomag9.greenpassdecoder.MainActivity.Companion.imgBitmap
import com.fuomag9.greenpassdecoder.MainActivity.Companion.showCamera
import com.fuomag9.greenpassdecoder.ui.theme.GreenPassDecoderTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.iot.cbor.CborMap
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.minvws.encoding.Base45
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.Inflater
import kotlin.properties.Delegates


class MainActivity : ComponentActivity() {
    @ExperimentalPermissionsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        activity = this
        super.onCreate(savedInstanceState)
        setContent {
            GreenPassDecoderTheme {
                Surface(color = MaterialTheme.colors.background) {
                    MainFunction()
                }
            }

        }
    }

    companion object {
        var activity by Delegates.notNull<Activity>()
        lateinit var showCamera: MutableState<Boolean>
        lateinit var ShowResults: MutableState<Boolean>
        lateinit var decodedPass: MutableState<String>
        lateinit var imageUri: MutableState<Uri?>
        lateinit var imgBitmap: MutableState<Bitmap?>
    }

}


@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    navController: NavHostController

) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context).apply {
                this.scaleType = scaleType
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Preview is incorrectly scaled in Compose on some devices without this
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                try {
                    // Must unbind the use-cases before rebinding them.
                    cameraProvider.unbindAll()


                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), { imgProxy ->
                        val mediaImage = imgProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imgProxy.imageInfo.rotationDegrees
                            )
                            decodeQRCode(image, cameraProvider)
                            imgProxy.close() //Todo: fix race condition by moving this inside the function (probably this is the cause of double navigate via camera)

                        }
                    })

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, imageAnalysis, preview
                    )
                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        })
}

@ExperimentalPermissionsApi
@Composable
fun MainFunction() {
    val context = LocalContext.current
    val navController = rememberNavController()
    imageUri = remember { mutableStateOf(null) }
    imgBitmap = remember { mutableStateOf(null) }
    showCamera = rememberSaveable { mutableStateOf(false) }
    ShowResults = rememberSaveable { mutableStateOf(false) }
    decodedPass = rememberSaveable { mutableStateOf("") }


    NavHost(navController = navController, startDestination = "AppStart") {
        composable("AppStart") { AppStart(navController = navController) }
        composable("Camera") { Camera(navController = navController) }

    }


}

@ExperimentalPermissionsApi
@Composable
fun AppStart(navController: NavController) {
    val ctx = LocalContext.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    //showCamera.value = false
    ShowResults.value = false

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract =
        ActivityResultContracts.GetContent()
    ) {
        if (it != null) {
            runBlocking {
                coroutineScope.launch(Dispatchers.IO) {
                    imgBitmap.value =

                        Glide
                            .with(ctx)
                            .asBitmap()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .load(it)
                            .fitCenter()
                            .override(1280, 768)
                            .submit().get()
                }.join()
            }
            if (imgBitmap.value != null) {
                ShowResults.value = true
                showCamera.value = false
                try {
                    val bitmap = imgBitmap.value
                    val width = bitmap!!.width
                    val height = bitmap.height
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    //bitmap.recycle()
                    val source = RGBLuminanceSource(width, height, pixels)
                    val bBitmap = BinaryBitmap(HybridBinarizer(source))
                    val reader = MultiFormatReader()
                    val result = reader.decodeWithState(bBitmap).toString()
                    decodedPass.value = truedecodepassdata(result)
                    navController.navigate("Camera")
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Failed to find a valid QR code",
                        Toast.LENGTH_SHORT
                    ).show()
                    decodedPass.value = ""
                }

            }
        }
    }


    Scaffold(topBar = {
        TopAppBar(
            title = { Text("GreenPass Decoder") },
            actions = {
                SoundIconButton(onClick = {
                    context.startActivity(
                        Intent(
                            context,
                            OssLicensesMenuActivity::class.java
                        )
                    )
                })

                {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )
    }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                //RequiresCameraPermission { }
                SoundButton(onClick = {
                    showCamera.value = true

                    if (!cameraPermissionState.hasPermission) {
                        cameraPermissionState.launchPermissionRequest()
                    }
                    navController.navigate("Camera")

                }) {
                    Text("Scan for Green Pass")
                }
                SoundButton(onClick = {
                    launcher.launch("image/*")


                }) {
                    Text("Load from gallery")
                }
            }


        }
    }


}

@Composable
fun Camera(navController: NavHostController) {
    if (showCamera.value) {
        CameraPreview(navController = navController)
    } else {
        Results(navController = navController)
    }
}

@Composable
fun Results(navController: NavHostController) {
    val ctx = LocalContext.current
    imgBitmap.value = null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        TextField(
            value = decodedPass.value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.weight(1f)
        )
        SoundButton(onClick = {
            val clipboard = ContextCompat.getSystemService(ctx, ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("", decodedPass.value))
            Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Copy to clipboard")
        }
    }

}


fun decodeQRCode(image: InputImage, cameraProvider: ProcessCameraProvider? = null) {
    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE
        )
        .build()
    val scanner = BarcodeScanning.getClient(options)

    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                try {
                    decodedPass.value = truedecodepassdata(barcode.rawValue!!)
                    showCamera.value = false
                    ShowResults.value = false
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                }
            }
        }
}

fun truedecodepassdata(data: String): String {
    val withoutPrefix: String = data.substring(4)
    val bytecompressed: ByteArray = Base45.getDecoder().decode(withoutPrefix)
    val inflater = Inflater()
    inflater.setInput(bytecompressed)
    val outputStream = ByteArrayOutputStream(bytecompressed.size)
    val buffer = ByteArray(10000)
    while (!inflater.finished()) {
        val count: Int = inflater.inflate(buffer)
        outputStream.write(buffer, 0, count)
    }
    val a: Message = Encrypt0Message.DecodeFromBytes(outputStream.toByteArray())
    val cborMap: CborMap = CborMap.createFromCborByteArray(a.GetContent())
    return cborMap.toString(2)
}