package com.firstclub.membership.web;

import com.firstclub.membership.dto.AdminBenefitDtos.TierBenefitAdminResponse;
import com.firstclub.membership.dto.AdminBenefitDtos.UpsertTierBenefitRequest;
import com.firstclub.membership.service.AdminBenefitService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Protected, intentionally narrow runtime configuration of tier perks only.
 * Authentication is enforced centrally by {@link AdminApiKeyInterceptor}. */
@RestController
@RequestMapping("/api/admin")
public class AdminBenefitController {

    private final AdminBenefitService adminBenefitService;

    public AdminBenefitController(AdminBenefitService adminBenefitService) {
        this.adminBenefitService = adminBenefitService;
    }

    @GetMapping("/tiers/{tierId}/benefits")
    public List<TierBenefitAdminResponse> list(@PathVariable Long tierId) {
        return adminBenefitService.list(tierId);
    }

    @PostMapping("/tiers/{tierId}/benefits")
    public ResponseEntity<TierBenefitAdminResponse> add(@PathVariable Long tierId,
                                                          @Valid @RequestBody UpsertTierBenefitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminBenefitService.add(tierId, request));
    }

    @PatchMapping("/benefits/{benefitId}")
    public TierBenefitAdminResponse update(@PathVariable Long benefitId,
                                           @Valid @RequestBody UpsertTierBenefitRequest request) {
        return adminBenefitService.update(benefitId, request);
    }
}
