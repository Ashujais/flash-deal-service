package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.repository.DealRepository
import java.util.*

class DealService(private val dealRepository: DealRepository) {

    fun createDeal(user: AuthUser, req: CreateDealRequest): DealResponse {
        if (user.role != UserRole.RESTAURANT_ADMIN || user.restaurantId == null) {
            throw AppException.Forbidden("Only restaurant admins can create deals")
        }

        val title = req.title?.trim()
        if (title.isNullOrEmpty() || title.length > 100) {
            throw AppException.ValidationFailed("Title must be between 1 and 100 characters")
        }

        val pricePaise = req.pricePaise
        if (pricePaise == null || pricePaise < 1 || pricePaise > 1_000_000) {
            throw AppException.ValidationFailed("price_paise must be between 1 and 1,000,000")
        }

        val stockTotal = req.stockTotal
        if (stockTotal == null || stockTotal < 1 || stockTotal > 10_000) {
            throw AppException.ValidationFailed("stock_total must be between 1 and 10,000")
        }

        if (req.startsAt.isNullOrBlank() || req.endsAt.isNullOrBlank()) {
            throw AppException.ValidationFailed("starts_at and ends_at are required")
        }

        val startsAt = parseIsoInstant(req.startsAt)
        val endsAt = parseIsoInstant(req.endsAt)

        if (!startsAt.isBefore(endsAt)) {
            throw AppException.ValidationFailed("starts_at must be strictly before ends_at")
        }

        val dealId = UUID.randomUUID()
        return dealRepository.create(
            id = dealId,
            restaurantId = user.restaurantId,
            title = title,
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            startsAt = startsAt,
            endsAt = endsAt
        )
    }

    fun getDeal(dealId: UUID): DealResponse {
        val deal = dealRepository.findById(dealId)
            ?: throw AppException.NotFound("Deal not found")
        return deal.toResponse()
    }
}
