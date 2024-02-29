package cat.dam.andy.aws_s3_compose

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cat.dam.andy.aws_s3_compose.ui.theme.AWS_S3_composeTheme
import coil.compose.rememberAsyncImagePainter
import com.amazonaws.AmazonClientException
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


data class DownloadState(
    val isDownloading: Boolean,
    val progressPercentage: Float
)
class MainActivity : ComponentActivity() {
    private val context: Context by lazy { this }
    private var uriPhotoImage: Uri? = null
    private val viewModel by viewModels<MyViewModel>()
    private lateinit var launcherTakePicture: ActivityResultLauncher<Intent>
    private var idDevice: String? = null
    private var uploadPercentage by mutableStateOf<Int?>(null)

    //    by mutableStateOf<ActivityResultLauncher<Intent>?>(null)
    private val launcherGallery = rememberLauncher { handleGalleryResult(it) }
    private lateinit var permissionManager: PermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        launcherTakePicture = rememberLauncherTakePicture(launcherTakePictureCallback)
        setContent {
            AWS_S3_composeTheme {
                MainScreen(viewModel)
            }
        }
    }

    // Funcions del Compose que utilitzen ActivityResultLauncher per gestionar els resultats
    private fun rememberLauncher(onResult: (ActivityResult) -> Unit): ActivityResultLauncher<Intent> {
        return registerForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult)
    }

    private fun rememberLauncherTakePicture(onResult: (ActivityResult) -> Unit): ActivityResultLauncher<Intent> {
        return rememberLauncher(onResult)
    }

    @Composable
    fun MainScreen(viewModel: MyViewModel = remember { MyViewModel() }, modifier: Modifier = Modifier) {
        val s3Helper = S3Helper()
        s3Helper.initS3Helper(context)
        var isUploading by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            DisplayImage()
            Button(
                onClick = {
                    handleTakePictureButtonClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Take Picture")
            }

            Button(
                onClick = {
                    handleGalleryButtonClick(launcherGallery)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Open Gallery")
            }

            Button(
                onClick = {
                        handleCloudStorageClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Cloud, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Save to cloud")
            }

            uploadPercentage?.let {
                when {
                    it == 100 -> {} // amagar el text / completament carregat
                    it == -1 -> {} // amagar el text / mostrar un error si convé
                    else -> Text(text = "Upload progress: $it%", modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    private @Composable
    fun DisplayImage() {
        val imageUri = viewModel.imageUri.value
        //Utilitza llibreria Coil
        if (imageUri != null) {
            val painter = rememberAsyncImagePainter(model = imageUri)
            Image(
                painter = painter,
                contentDescription = "Selected Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(8.dp)
            )
        } else {
            // Mostra una imatge predeterminada o gestiona l'absència d'imatge
            // en funció de les teves necessitats.
        }
    }

    private fun handleGalleryButtonClick(launcherGallery: ActivityResultLauncher<Intent>) {
        handleMediaAccessGallery(launcherGallery)
    }

    private fun handleTakePictureButtonClick() {
        permissionManager
            .request(Permissions.PermCamImgSave)
            .rationale(
                description = "Permissions needed to take and save pictures",
                title = "Camera Access and storage Permission"
            )
            .checkAndRequestPermission { isGranted ->
                if (isGranted) {
                    getPictureImage()
                } else {
                    Toast.makeText(
                        context,
                        "Camera permission denied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    @SuppressLint("HardwareIds")
    private fun handleCloudStorageClick() {
        CoroutineScope(Dispatchers.Main).launch { // This coroutine runs on the main thread
            val imageUri = viewModel.imageUri.value
            println("Image URI: $imageUri")
            if (imageUri == null) {
                Toast.makeText(
                    this@MainActivity,
                    resources.getString(R.string.no_image_selected),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val s3Helper = S3Helper()
                s3Helper.initS3Helper(context)
                idDevice = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val mimeType = contentResolver.getType(imageUri)
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                val cloudFilePath: String = idDevice + "_" + imageUri.lastPathSegment + ".$extension"
                val inputStream = contentResolver.openInputStream(imageUri)
                if (inputStream != null) {
                    s3Helper.uploadFileAsync(
                        context,
                        inputStream,
                        cloudFilePath,
                        onProgress = { bytesCurrent, bytesTotal ->
                            uploadPercentage = ((bytesCurrent.toFloat() / bytesTotal.toFloat()) * 100).toInt()
                        },
                        onUploadComplete = {
                            // Show a Toast message instead of changing uploadPercentage to 100
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Upload complete!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onError = { exception -> // Add an onError function
                            uploadPercentage = -1 // Update uploadPercentage to -1 in case of error
                            if (exception is AmazonClientException) {
                                val errorMessage = exception.message
                                val regex = "<Message>(.*?)</Message>".toRegex()
                                val matchResult = regex.find(errorMessage ?: "")
                                val specificErrorMessage = matchResult?.groups?.get(1)?.value
                                if (specificErrorMessage != null) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            specificErrorMessage,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    println("Error uploading file: $specificErrorMessage")
                                } else {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            errorMessage,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    println("Error uploading file: $errorMessage")
                                }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        exception.message,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                println("Error uploading file: ${exception.message}")
                            }
                        }
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        resources.getString(R.string.save_to_cloud_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getPictureImage() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "Image")
            put(MediaStore.Images.Media.DESCRIPTION, "From your Camera")
        }
        uriPhotoImage =
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uriPhotoImage)
        }
        // Utilitza el launcherTakePicture ja inicialitzat
        launcherTakePicture.launch(cameraIntent)
    }

    private fun handleMediaAccessGallery(launcherGallery: ActivityResultLauncher<Intent>) {
        permissionManager
            .request(Permissions.PermImagePick)
            .rationale(
                description = "Permission needed to access media",
                title = "Media Access Permission"
            )
            .checkAndRequestPermission { isGranted ->
                if (isGranted) {
                    launcherGallery.launch(getGalleryIntent())
                } else {
                    Toast.makeText(
                        context,
                        "Gallery permission denied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun handleGalleryResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val pickedImageUri = data.data
                viewModel.imageUri.value = pickedImageUri
            }
        } else {
            Toast.makeText(context, "Cancelled...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getGalleryIntent(): Intent {
        val intent: Intent = if (apiRequiresScopePermissions()) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_PICK)
        }

        try {
            if (!apiRequiresScopePermissions()) {
                intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                intent.action = Intent.ACTION_GET_CONTENT
            }

            if (intent.resolveActivity(packageManager) == null) {
                Toast.makeText(
                    context, "El seu dispositiu no permet accedir a la galeria",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return intent
    }

    private val launcherTakePictureCallback = { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            // La foto s'ha capturat amb èxit i es pot afegir a la galeria aquí si és necessari.
            Toast.makeText(this, "Image captured", Toast.LENGTH_SHORT).show()
            viewModel.imageUri.value = uriPhotoImage
            if (!apiRequiresScopePermissions()) {
                refreshGallery() //refresca gallery per veure nou fitxer (OLD API)
            }
            //Intent data = result.getData(); //si volguessim només la miniatura
        } else {
            // L'usuari pot haver cancel·lat la presa de la foto o pot haver-hi altres problemes.
            Toast.makeText(
                context, getString(R.string.photo_capture_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun refreshGallery() {
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = uriPhotoImage
        this.sendBroadcast(mediaScanIntent)
    }

    private fun apiRequiresScopePermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
            Build.VERSION_CODES.R
        ) >= 2
    }

}