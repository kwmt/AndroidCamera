package net.kwmt27.camera2sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation

class PermissionFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasPermission(requireContext())) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                PERMISSIONS_REQUIRED,
                PERMISSIONS_REQUEST_CODE
            )
            return
        }

        Navigation.findNavController(requireActivity(), R.id.fragment_container)
            .navigate(PermissionFragmentDirections.actionPermissionsFragmentToCameraPreviewFragment())
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 10
        private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)
        fun hasPermission(context: Context): Boolean {
            return PERMISSIONS_REQUIRED.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }


}
