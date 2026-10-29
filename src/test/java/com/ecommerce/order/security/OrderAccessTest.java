package com.ecommerce.order.security;

import com.ecommerce.order.exception.BadRequestException;
import com.ecommerce.order.exception.ForbiddenException;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.exception.UnauthorizedException;
import com.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@Tag("security")
@ExtendWith(MockitoExtension.class)
class OrderAccessTest {

    @Mock private OrderRepository orderRepository;
    @InjectMocks private OrderAccess access;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void noIdentity_is401_onEveryRule(String username) {
        assertThatThrownBy(() -> access.requireAdmin(username, "ADMIN")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.requireOwnerOrAdmin(username, "ADMIN", 1L)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.requireCustomerOrAdmin(username, "ADMIN", "alice")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.resolveCustomer(username, "ADMIN", "alice")).isInstanceOf(UnauthorizedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", " ADMIN", "ADMIN ", "ROLE_ADMIN", "CUSTOMER", ""})
    void onlyTheExactRoleAdminIsAdmin(String role) {
        assertThatThrownBy(() -> access.requireAdmin("root", role)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> access.requireCustomerOrAdmin("root", role, "alice")).isInstanceOf(ForbiddenException.class);
        assertThat(OrderAccess.isAdmin(role)).isFalse();
    }

    @Test
    void adminPasses() {
        assertThatCode(() -> access.requireAdmin("root", "ADMIN")).doesNotThrowAnyException();
        assertThatCode(() -> access.requireOwnerOrAdmin("root", "ADMIN", 1L)).doesNotThrowAnyException();
        assertThat(access.resolveCustomer("root", "ADMIN", "alice")).isEqualTo("alice");
    }

    @Test
    void owner_passes_otherCustomer_is403() {
        given(orderRepository.findCustomerIdByOrderId(7L)).willReturn(Optional.of("alice"));

        assertThatCode(() -> access.requireOwnerOrAdmin("alice", "CUSTOMER", 7L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireOwnerOrAdmin("bob", "CUSTOMER", 7L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unknownOrder_is404_forACustomer() {
        given(orderRepository.findCustomerIdByOrderId(8L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwnerOrAdmin("alice", "CUSTOMER", 8L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void customerListing_isForTheCustomerOnly() {
        assertThatCode(() -> access.requireCustomerOrAdmin("alice", "CUSTOMER", "alice")).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireCustomerOrAdmin("alice", "CUSTOMER", "bob")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void create_defaultsToTheCaller_andRefusesAnotherCustomerForNonAdmins() {
        assertThat(access.resolveCustomer("alice", "CUSTOMER", null)).isEqualTo("alice");
        assertThat(access.resolveCustomer("alice", "CUSTOMER", "alice")).isEqualTo("alice");
        assertThatThrownBy(() -> access.resolveCustomer("alice", "CUSTOMER", "bob")).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "a\u0000b", "a\nb", "a\rb"})
    void create_rejectsBlankOrControlCharacterCustomerIds(String customerId) {
        assertThatThrownBy(() -> access.resolveCustomer("root", "ADMIN", customerId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_rejectsOverlongCustomerId() {
        assertThatThrownBy(() -> access.resolveCustomer("root", "ADMIN", "x".repeat(256))).isInstanceOf(BadRequestException.class);
        assertThat(access.resolveCustomer("root", "ADMIN", "x".repeat(255))).hasSize(255);
    }
}
