package cat.dam.andy.aws_s3_compose

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.services.s3.model.S3ObjectSummary


open class MyTransferListenerImpl : TransferListener {
    override fun onStateChanged(id: Int, state: TransferState?) {
        // Implementa la lògica per als canvis d'estat aquí
        println("State: $state")
    }

    override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
        // Implementa la lògica per al progrés aquí
        println("Progress: $bytesCurrent/$bytesTotal")
    }

    override fun onError(id: Int, ex: Exception?) {
        // Implementa la lògica per als errors aquí
        if (ex != null) {
            println("Error: ${ex.message}")
        }
    }
}

class MyViewModel : ViewModel() {
    val imageUri = mutableStateOf<Uri?>(null)
    private val _fileList = MutableLiveData<List<S3ObjectSummary>>()
    val fileList: LiveData<List<S3ObjectSummary>> get() = _fileList

    private val _isDownloadSuccessful = MutableLiveData<Boolean>()
    val isDownloadSuccessful: LiveData<Boolean> get() = _isDownloadSuccessful

    fun updateFileList(newFileList: List<S3ObjectSummary>) {
        _fileList.postValue(newFileList) // El liveData s'ha d'actualitzar al fil principal
    }

}
