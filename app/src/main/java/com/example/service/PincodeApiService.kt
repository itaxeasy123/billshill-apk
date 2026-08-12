package com.example.service

import com.example.utils.PincodeLocationDetails
import com.example.utils.PostalPincodeService

/**
 * Service API layer for Indian Post Office Public API integration.
 * Enables auto-population of City, District, State, and Country given a 6-digit PIN code.
 */
object PincodeApiService {

    suspend fun lookupPincode(pincode: String): PincodeLocationDetails {
        return PostalPincodeService.fetchPincodeDetails(pincode)
    }
}
