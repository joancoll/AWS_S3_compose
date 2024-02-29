package cat.dam.andy.aws_s3_compose

import android.content.Context
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AccessControlList
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.S3ObjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class S3Helper() {

    private var s3: AmazonS3? = null
    private var transferUtility: TransferUtility? = null
    private var bucketName: String? = null
    private var region: Region? = null

    fun initS3Helper(context: Context,
                     accessKey: String=AWS_KEYS.AWS_ACCESS_KEY,
                     secretKey: String=AWS_KEYS.AWS_SECRET_ACCESS_KEY,
                     sessionToken: String=AWS_KEYS.AWS_SESSION_TOKEN,
                     bucketName: String=AWS_KEYS.BUCKET_NAME,
                     region: Region =AWS_KEYS.REGION
    ) {
        this.bucketName = bucketName
        this.region = region
        s3 = AmazonS3Client(
            BasicSessionCredentials(
                AWS_KEYS.AWS_ACCESS_KEY,
                AWS_KEYS.AWS_SECRET_ACCESS_KEY,
                AWS_KEYS.AWS_SESSION_TOKEN
            ),
            Region.getRegion(AWS_KEYS.REGION.name)
        )
        s3!!.setRegion(region)
        transferUtility = TransferUtility.builder()
            .context(context)
            .s3Client(s3)
            .defaultBucket(bucketName)
            .build()
    }

    fun uploadFile(
        inputStream: InputStream,
        key: String,
        onProgress: (bytesCurrent: Long, bytesTotal: Long) -> Unit,
        onUploadComplete: () -> Unit
    ): TransferObserver {
        val tempFile = File.createTempFile("temp", null)

        val observer: TransferObserver = transferUtility!!.upload(
            bucketName,
            key,
            tempFile
        )
        observer.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                // No cal implementar res aquí
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                onProgress(bytesCurrent, bytesTotal)
            }

            override fun onError(id: Int, ex: Exception?) {
                // No cal implementar res aquí
            }
        })

        // Utilitza un fil per escriure l'InputStream al fitxer temporal
        Thread {
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onUploadComplete()
        }.start()

        return observer
    }

    fun downloadFile(
        key: String,
        filePath: String,
        listener: TransferListener
    ): TransferObserver {
        val observer: TransferObserver = transferUtility!!.download(
            bucketName,
            key,
            java.io.File(filePath)
        )
        observer.setTransferListener(listener)
        return observer
    }

    fun deleteFile(key: String) {
        s3!!.deleteObject(bucketName, key)
    }

    fun listFiles(): List<S3ObjectSummary> {
        return runBlocking(Dispatchers.IO) {
            val objectListing: ObjectListing = s3!!.listObjects(bucketName)
            objectListing.objectSummaries
        }
    }

    fun getObjectMetadata(key: String): ObjectMetadata {
        return s3!!.getObjectMetadata(bucketName, key)
    }

    fun getObject(key: String): S3Object {
        return s3!!.getObject(bucketName, key)
    }

    fun getObjectAcl(key: String): AccessControlList {
        return s3!!.getObjectAcl(bucketName, key)
    }

    fun putObjectAcl(key: String, acl: AccessControlList) {
        s3!!.setObjectAcl(bucketName, key, acl)
    }

    fun getObjectUrl(key: String): String {
        return s3!!.getUrl(bucketName, key).toString()
    }

    suspend fun uploadFileAsync(
        context: Context,
        inputStream: InputStream,
        remoteFileName: String,
        onProgress: (bytesCurrent: Long, bytesTotal: Long) -> Unit,
        onUploadComplete: () -> Unit,
        onError: (Exception) -> Unit // Afegeix una funció onError
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Intenta obtenir la llista d'objectes del bucket
                s3!!.listObjects(bucketName)
            } catch (e: Exception) {
                // Si aquesta operació falla, això indica que no es pot establir una connexió amb el servidor
                onError(e)
                return@withContext
            }

            val transferListener = object : MyTransferListenerImpl() {
                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    super.onProgressChanged(id, bytesCurrent, bytesTotal)
                    onProgress(bytesCurrent, bytesTotal)
                }

                override fun onError(id: Int, ex: Exception?) {
                    super.onError(id, ex)
                    ex?.let { onError(it) } // Comprova si ex és nul·la abans de cridar onError
                }
            }
            val observer = uploadFile(inputStream, remoteFileName, onProgress, onUploadComplete)
            if (observer.state != TransferState.COMPLETED && observer.state != TransferState.WAITING) {
                onError(Exception("Upload failed with state: ${observer.state}"))
            }
        }
    }

    suspend fun downloadFileAsync(
        context: Context,
        remoteFileName: String,
        onProgress: (bytesCurrent: Long, bytesTotal: Long) -> Unit,
        onDownloadComplete: () -> Unit
    ): String? {
        return withContext(Dispatchers.IO) {
            val externalStorageDir = context.getExternalFilesDir(null)
            val localFilePath = "$externalStorageDir/$remoteFileName"

            val transferListener = object : MyTransferListenerImpl() {
                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    super.onProgressChanged(id, bytesCurrent, bytesTotal)
                    onProgress(bytesCurrent, bytesTotal)
                }
            }

            val downloadObserver = downloadFile(remoteFileName, localFilePath, transferListener)

            val isDownloadSuccessful = downloadObserver.state == TransferState.COMPLETED
            if (isDownloadSuccessful) {
                onDownloadComplete()
            }

            // Actualitzar el progrés de descàrrega
            onProgress(downloadObserver.bytesTransferred, downloadObserver.bytesTotal)

            if (isDownloadSuccessful) {
                localFilePath
            } else null
        }
    }

}