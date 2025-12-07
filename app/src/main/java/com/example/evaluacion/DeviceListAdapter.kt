package com.example.evaluacion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class DeviceListAdapter(
    private val devices: List<Device>,
    private val onClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {


    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val deviceNameTextView: TextView = itemView.findViewById(R.id.tv_device_name)

        // Método para "atar" los datos del dispositivo a la vista
        fun bind(device: Device, onClick: (Device) -> Unit) {
            deviceNameTextView.text = device.name
            // Configurar el clic para todo el item
            itemView.setOnClickListener {
                onClick(device)
            }
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_item_device, parent, false)
        return DeviceViewHolder(view)
    }


    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device, onClick)
    }


    override fun getItemCount(): Int {
        return devices.size
    }
}