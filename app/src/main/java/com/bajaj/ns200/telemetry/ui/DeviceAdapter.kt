package com.bajaj.ns200.telemetry.ui

import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bajaj.ns200.telemetry.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onDeviceSelected: (ScanResult) -> Unit
) : ListAdapter<ScanResult, DeviceAdapter.ViewHolder>(DiffCallback()) {

    private var selectedPosition: Int = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    fun getSelectedDevice(): ScanResult? {
        return if (selectedPosition != RecyclerView.NO_POSITION) getItem(selectedPosition)
        else null
    }

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: ScanResult, isSelected: Boolean) {
            with(binding) {
                textDeviceName.text = result.device.name ?: "Unknown Device"
                textDeviceAddress.text = result.device.address
                textDeviceRssi.text = "${result.rssi} dBm"
                root.isSelected = isSelected
                root.setBackgroundResource(
                    if (isSelected) android.R.color.holo_blue_light
                    else android.R.color.transparent
                )
                root.setOnClickListener {
                    val prev = selectedPosition
                    selectedPosition = bindingAdapterPosition
                    if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
                    notifyItemChanged(selectedPosition)
                    onDeviceSelected(result)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ScanResult>() {
        override fun areItemsTheSame(a: ScanResult, b: ScanResult) =
            a.device.address == b.device.address

        override fun areContentsTheSame(a: ScanResult, b: ScanResult) =
            a.rssi == b.rssi && a.device.name == b.device.name
    }
}
