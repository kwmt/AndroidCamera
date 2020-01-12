package net.kwmt27.camera2sample

import androidx.lifecycle.ViewModelProviders
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup


class CameraPreviewFragment : Fragment() {

    companion object {
        fun newInstance() = CameraPreviewFragment()
    }

    private lateinit var viewModel: CameraPreviewViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.camera_preview_fragment, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(CameraPreviewViewModel::class.java)
        // TODO: Use the ViewModel
    }

}
