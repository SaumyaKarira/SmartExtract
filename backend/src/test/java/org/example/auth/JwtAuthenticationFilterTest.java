package org.example.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtAuthenticationFilter — missing/invalid/expired JWT.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtService jwtService;

    @InjectMocks JwtAuthenticationFilter filter;

    @org.junit.jupiter.api.BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeader_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
    }

    @Test
    void bearerWithInvalidToken_doesNotAuthenticate_andChainContinues() throws Exception {
        when(jwtService.extractUserId("bad-token")).thenThrow(new RuntimeException("Invalid JWT"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        // SecurityContext must stay empty — Spring Security will then return 401
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Chain must still proceed so Spring Security handles the 401
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void bearerWithValidToken_setsAuthentication() throws Exception {
        when(jwtService.extractUserId("good-token")).thenReturn(42L);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(42L);
    }

    @Test
    void expiredToken_doesNotAuthenticate() throws Exception {
        when(jwtService.extractUserId("expired-token"))
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "JWT expired"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerAuthorizationHeader_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
    }
}

