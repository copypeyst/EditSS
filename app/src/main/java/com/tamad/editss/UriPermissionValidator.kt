package com.tamad.editss

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Centralized URI permission validation and safe file operations
 * Prevents crashes from stale URI permissions and provides safe file handling
 */
class UriPermissionValidator(private val context: Context) {
    
    private val contentResolver = context.contentResolver
    
    /**
     * Validates if URI still has required permissions
     */
    fun validateUriPermission(uri: Uri, requiredPermission: UriPermission = UriPermission.READ_WRITE): ValidationResult {
        return try {
            // Check if URI is valid format
            if (!isValidUri(uri)) {
                return ValidationResult.Invalid("Invalid URI format")
            }
            
            // Check persisted permissions for content URIs
            if (uri.scheme == "content") {
                val persistedPermissions = contentResolver.persistedUriPermissions
                val hasReadPermission = persistedPermissions.any { 
                    it.uri == uri && it.isReadPermission 
                }
                val hasWritePermission = persistedPermissions.any { 
                    it.uri == uri && it.isWritePermission 
                }
                
                return when (requiredPermission) {
                    UriPermission.READ_ONLY -> {
                        if (hasReadPermission) ValidationResult.Valid 
                        else ValidationResult.Revoked("Read permission revoked")
                    }
                    UriPermission.READ_WRITE -> {
                        if (hasReadPermission && hasWritePermission) ValidationResult.Valid
                        else ValidationResult.Revoked("Read/Write permission revoked")
                    }
                    UriPermission.WRITE_ONLY -> {
                        if (hasWritePermission) ValidationResult.Valid
                        else ValidationResult.Revoked("Write permission revoked")
                    }
                }
            }
            
            // For FileProvider URIs, check if file still exists
            if (uri.authority == "${context.packageName}.fileprovider") {
                val filePath = getFilePathFromFileProvider(uri)
                if (filePath != null && File(filePath).exists()) {
                    return ValidationResult.Valid
                } else {
                    return ValidationResult.Revoked("File no longer exists")
                }
            }
            
            // For MediaStore URIs, try to query to validate
            if (uri.toString().contains("media")) {
                try {
                    val projection = arrayOf(MediaStore.Images.Media._ID)
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            return ValidationResult.Valid
                        } else {
                            return ValidationResult.Revoked("MediaStore entry no longer exists")
                        }
                    }
                } catch (e: SecurityException) {
                    return ValidationResult.Revoked("MediaStore permission revoked")
                }
            }
            
            ValidationResult.Valid // Default to valid for unknown URI types
            
        } catch (e: SecurityException) {
            ValidationResult.Revoked("Security exception: ${e.message}")
        } catch (e: Exception) {
            ValidationResult.Invalid("Validation error: ${e.message}")
        }
    }
    
    /**
     * Safe file output stream with permission validation
     */
    fun safeOpenOutputStream(
        uri: Uri, 
        mode: String = "wt",
        requiredPermission: UriPermission = UriPermission.WRITE_ONLY
    ): SafeFileOperation {
        return try {
            val validation = validateUriPermission(uri, requiredPermission)
            if (validation !is ValidationResult.Valid) {
                return SafeFileOperation.Failed(validation.errorMessage ?: "Permission validation failed")
            }
            
            val outputStream = try {
                contentResolver.openOutputStream(uri, mode)
            } catch (e: SecurityException) {
                return SafeFileOperation.Failed("Write permission denied: ${e.message}")
            } catch (e: Exception) {
                return SafeFileOperation.Failed("Failed to open output stream: ${e.message}")
            }
            
            if (outputStream != null) {
                SafeFileOperation.Success(outputStream)
            } else {
                SafeFileOperation.Failed("Output stream is null")
            }
            
        } catch (e: Exception) {
            SafeFileOperation.Failed("Critical error: ${e.message}")
        }
    }
    
    /**
     * Safe file input stream with permission validation
     */
    fun safeOpenInputStream(
        uri: Uri,
        requiredPermission: UriPermission = UriPermission.READ_ONLY
    ): SafeFileOperation {
        return try {
            val validation = validateUriPermission(uri, requiredPermission)
            if (validation !is ValidationResult.Valid) {
                return SafeFileOperation.Failed(validation.errorMessage ?: "Permission validation failed")
            }
            
            val inputStream = try {
                contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                return SafeFileOperation.Failed("Read permission denied: ${e.message}")
            } catch (e: Exception) {
                return SafeFileOperation.Failed("Failed to open input stream: ${e.message}")
            }
            
            if (inputStream != null) {
                SafeFileOperation.Success(inputStream)
            } else {
                SafeFileOperation.Failed("Input stream is null")
            }
            
        } catch (e: Exception) {
            SafeFileOperation.Failed("Critical error: ${e.message}")
        }
    }
    
    /**
     * Safe file deletion with proper cleanup
     */
    fun safeDeleteFile(uri: Uri): DeletionResult {
        return try {
            when {
                uri.scheme == "file" -> {
                    // Direct file URI
                    val file = File(uri.path ?: "")
                    if (file.exists()) {
                        val deleted = file.delete()
                        if (deleted) {
                            DeletionResult.Success
                        } else {
                            DeletionResult.Failed("Failed to delete file")
                        }
                    } else {
                        DeletionResult.AlreadyDeleted
                    }
                }
                uri.authority == "${context.packageName}.fileprovider" -> {
                    // FileProvider URI - try to delete the underlying file
                    val filePath = getFilePathFromFileProvider(uri)
                    if (filePath != null) {
                        val file = File(filePath)
                        if (file.exists()) {
                            val deleted = file.delete()
                            if (deleted) {
                                DeletionResult.Success
                            } else {
                                DeletionResult.Failed("Failed to delete FileProvider file")
                            }
                        } else {
                            DeletionResult.AlreadyDeleted
                        }
                    } else {
                        DeletionResult.Failed("Could not resolve FileProvider path")
                    }
                }
                else -> {
                    // Content URI - cannot directly delete, release permissions
                    try {
                        contentResolver.releasePersistableUriPermission(
                            uri, 
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        DeletionResult.Success
                    } catch (e: Exception) {
                        DeletionResult.Failed("Failed to release permissions: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            DeletionResult.Failed("Critical deletion error: ${e.message}")
        }
    }
    
    /**
     * Batch cleanup for multiple URIs
     */
    suspend fun safeBatchCleanup(uris: List<Uri>): List<Pair<Uri, DeletionResult>> {
        return withContext(Dispatchers.IO) {
            uris.map { uri ->
                uri to safeDeleteFile(uri)
            }
        }
    }
    
    /**
     * Check if URI is in valid format
     */
    private fun isValidUri(uri: Uri): Boolean {
        return try {
            uri.scheme != null && uri.host != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Extract file path from FileProvider URI
     */
    private fun getFilePathFromFileProvider(uri: Uri): String? {
        return try {
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                // FileProvider URIs typically have format: /external_files/Pictures/temp.jpg
                val relativePath = pathSegments.joinToString("/")
                val externalFilesDir = context.getExternalFilesDir(null)
                if (externalFilesDir != null) {
                    File(externalFilesDir, relativePath).absolutePath
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Permission levels for URI operations
 */
enum class UriPermission {
    READ_ONLY,
    WRITE_ONLY,
    READ_WRITE
}

/**
 * Result of URI permission validation
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Revoked(val errorMessage: String) : ValidationResult()
    data class Invalid(val errorMessage: String) : ValidationResult()
    
    val isValid: Boolean get() = this is Valid
    val errorMessage: String? get() = when (this) {
        is Valid -> null
        is Revoked -> errorMessage
        is Invalid -> errorMessage
    }
}

/**
 * Result of file operations
 */
sealed class SafeFileOperation {
    data class Success(val stream: java.io.OutputStream) : SafeFileOperation()
    data class SuccessInput(val stream: java.io.InputStream) : SafeFileOperation()
    data class Failed(val errorMessage: String) : SafeFileOperation()
    
    val isSuccess: Boolean get() = this is Success || this is SuccessInput
    val stream: java.io.OutputStream? get() = (this as? Success)?.stream
    val inputStream: java.io.InputStream? get() = (this as? SuccessInput)?.stream
    val errorMessage: String? get() = (this as? Failed)?.errorMessage
}

/**
 * Result of file deletion operations
 */
sealed class DeletionResult {
    data object Success : DeletionResult()
    data object AlreadyDeleted : DeletionResult()
    data class Failed(val errorMessage: String) : DeletionResult()
    
    val isSuccess: Boolean get() = this is Success || this is AlreadyDeleted
    val errorMessage: String? get() = (this as? Failed)?.errorMessage
}