package com.example.scannerkotlin.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.example.scannerkotlin.R
import com.example.scannerkotlin.model.Product

class CustomProductDialogAdapter(
    context: Context,
    private val products: List<Product>
) : ArrayAdapter<Product>(context, 0, products) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val viewHolder: ViewHolder

        if (convertView == null) {
            view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_product_item, parent, false)
            viewHolder = ViewHolder(view.findViewById(R.id.tvDialogProductName))
            view.tag = viewHolder
        } else {
            view = convertView
            viewHolder = view.tag as ViewHolder
        }

        val product = getItem(position)
        viewHolder.nameTextView.text = product?.name ?: "Без имени"

        return view
    }


    private class ViewHolder(val nameTextView: TextView)

}