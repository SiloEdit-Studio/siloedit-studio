package com.example.photoeditor.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhotoViewModel : ViewModel() {
    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri = _currentUri.asStateFlow()

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap = _currentBitmap.asStateFlow()

    fun setImage(uri: Uri?) {
        _currentUri.value = uri
    }

    fun setBitmap(bitmap: Bitmap?) {
        _currentBitmap.value = bitmap
    }
}
