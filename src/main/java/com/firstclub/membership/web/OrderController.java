package com.firstclub.membership.web;

import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.dto.OrderDtos.OrderPlacedResponse;
import com.firstclub.membership.dto.OrderDtos.OrderResponse;
import com.firstclub.membership.dto.OrderDtos.PlaceOrderRequest;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.OrderService;
import com.firstclub.membership.service.OrderService.PlaceOrderResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Simulates order placement/cancellation so the tier-promotion flow (FR7) can be demoed
 * end-to-end without a real order/catalog subsystem, which is out of scope here.
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final SubscriptionRepository subscriptionRepository;

    public OrderController(OrderService orderService, SubscriptionRepository subscriptionRepository) {
        this.orderService = orderService;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostMapping("/users/{userId}/orders")
    public ResponseEntity<OrderPlacedResponse> placeOrder(@PathVariable Long userId,
                                                            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = orderService.placeOrder(userId, request.value());
        String currentTier = subscriptionRepository
                .findFirstByUserIdOrderByStartDateDesc(userId)
                .map(s -> s.getTier().getName().name())
                .orElse(null);
        OrderResponse orderResponse = toResponse(result.order());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderPlacedResponse(orderResponse, result.tierChanged(), currentTier));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable Long orderId) {
        return toResponse(orderService.cancelOrder(orderId));
    }

    private OrderResponse toResponse(OrderRecord order) {
        return new OrderResponse(order.getId(), order.getUser().getId(), order.getValue(),
                order.isCancelled(), order.getPlacedAt().toString());
    }
}
