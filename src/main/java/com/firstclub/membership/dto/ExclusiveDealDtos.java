package com.firstclub.membership.dto;

import java.math.BigDecimal;

public final class ExclusiveDealDtos {

    private ExclusiveDealDtos() {
    }

    /** A category-scoped deal available to the user's currently active membership tier. */
    public record ExclusiveDealResponse(String category, BigDecimal discountPercent) {
    }
}
