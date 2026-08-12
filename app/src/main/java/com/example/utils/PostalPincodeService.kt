package com.example.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class PincodeLocationDetails(
    val pincode: String,
    val postOfficeName: String,
    val district: String,
    val state: String,
    val country: String,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

object PostalPincodeService {

    suspend fun fetchPincodeDetails(pincode: String): PincodeLocationDetails = withContext(Dispatchers.IO) {
        val cleanPin = pincode.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it.isDigit() }) {
            return@withContext PincodeLocationDetails(
                pincode = cleanPin,
                postOfficeName = "",
                district = "",
                state = "",
                country = "",
                isSuccess = false,
                errorMessage = "Invalid 6-digit Pincode"
            )
        }

        try {
            val url = URL("https://api.postalpincode.in/pincode/$cleanPin")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseText)
                if (jsonArray.length() > 0) {
                    val rootObj = jsonArray.getJSONObject(0)
                    val status = rootObj.optString("Status", "")
                    if (status.equals("Success", ignoreCase = true)) {
                        val postOffices = rootObj.getJSONArray("PostOffice")
                        if (postOffices.length() > 0) {
                            val po = postOffices.getJSONObject(0)
                            val name = po.optString("Name", "")
                            val district = po.optString("District", "")
                            val state = po.optString("State", "")
                            val country = po.optString("Country", "India")
                            return@withContext PincodeLocationDetails(
                                pincode = cleanPin,
                                postOfficeName = name,
                                district = district,
                                state = state,
                                country = country,
                                isSuccess = true
                            )
                        }
                    }
                }
            }
            return@withContext PincodeLocationDetails(
                pincode = cleanPin,
                postOfficeName = "",
                district = "",
                state = "",
                country = "",
                isSuccess = false,
                errorMessage = "Pincode not found"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext PincodeLocationDetails(
                pincode = cleanPin,
                postOfficeName = "",
                district = "",
                state = "",
                country = "",
                isSuccess = false,
                errorMessage = e.localizedMessage ?: "Network error"
            )
        }
    }
}
