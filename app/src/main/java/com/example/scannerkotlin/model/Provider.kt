package com.example.scannerkotlin.model

import com.google.gson.annotations.SerializedName

data class Provider(
    @SerializedName("id")
    val id: Int?,

    @SerializedName("createdTime")
    val createdTime: String?,

    @SerializedName("updatedTime")
    val updatedTime: String?,

    @SerializedName("createdBy")
    val createdBy: Int?,

    @SerializedName("updatedBy")
    val updatedBy: Int?,

    @SerializedName("assignedById")
    val assignedById: Int?,

    @SerializedName("opened")
    val opened: String?,

    @SerializedName("title")
    val title: String?,

    @SerializedName("logo")
    val logo: String?,

    @SerializedName("address")
    val address: String?,

    @SerializedName("addressLegal")
    val addressLegal: String?,

    @SerializedName("bankingDetails")
    val bankingDetails: String?,

    @SerializedName("comments")
    val comments: String?,

    @SerializedName("leadId")
    val leadId: Int?,

    @SerializedName("webformId")
    val webformId: Int?,

    @SerializedName("originatorId")
    val originatorId: String?,

    @SerializedName("originId")
    val originId: String?,

    @SerializedName("originVersion")
    val originVersion: String?,

    @SerializedName("hasPhone")
    val hasPhone: String?,

    @SerializedName("hasEmail")
    val hasEmail: String?,

    @SerializedName("hasImol")
    val hasImol: String?,

    @SerializedName("isMyCompany")
    val isMyCompany: String?,

    @SerializedName("searchContent")
    val searchContent: String?,

    @SerializedName("categoryId")
    val categoryId: Int?,

    @SerializedName("lastActivityBy")
    val lastActivityBy: Int?,

    @SerializedName("lastActivityTime")
    val lastActivityTime: String?,

    @SerializedName("emailHome")
    val emailHome: String?,

    @SerializedName("emailWork")
    val emailWork: String?,

    @SerializedName("emailMailing")
    val emailMailing: String?,

    @SerializedName("phoneMobile")
    val phoneMobile: String?,

    @SerializedName("phoneWork")
    val phoneWork: String?,

    @SerializedName("phoneMailing")
    val phoneMailing: String?,

    @SerializedName("imol")
    val imol: String?,

    @SerializedName("email")
    val email: String?,

    @SerializedName("phone")
    val phone: String?,

    @SerializedName("ufLogo")
    val ufLogo: String?,

    @SerializedName("ufCrm_1741017981598")
    val ufCrmCustomField: String?,

    @SerializedName("ufStamp")
    val ufStamp: String?,

    @SerializedName("ufDirectorSign")
    val ufDirectorSign: String?,

    @SerializedName("ufAccountantSign")
    val ufAccountantSign: String?,

    @SerializedName("observers")
    val observers: List<Int>?,

    @SerializedName("contactIds")
    val contactIds: List<Int>?,

    @SerializedName("entityTypeId")
    val entityTypeId: Int?,

    @SerializedName("fm")
    val fm: List<Any>?
)