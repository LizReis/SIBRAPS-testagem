package com.pet.buscaativa.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.UserDetailsServiceImpl;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class SecurityFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserDetailsServiceImpl userDetailsService;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final HandlerExceptionResolver exceptionResolver;

    public SecurityFilter(TokenService tokenService, UserDetailsServiceImpl userDetailsService,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            authenticate(request);
        } catch (UsernameNotFoundException failure) {
            authenticationEntryPoint.commence(request, response, failure);
            return;
        } catch (RuntimeException failure) {
            if (exceptionResolver.resolveException(request, response, null, failure) == null) throw failure;
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        String token = recuperarToken(request);
        if (token == null) return;

        var identidade = tokenService.verificarIdentidade(token);
        if (identidade.isEmpty()) return;

        var usuario = userDetailsService.loadUserByUsername(identidade.get().email());
        if (!(usuario instanceof Usuario conta) || !identidade.get().usuario().equals(conta.getIdPublico())
                || !conta.isEnabled() || !conta.isAccountNonLocked() || !conta.isAccountNonExpired()
                || !conta.isCredentialsNonExpired()) return;
        var authentication = new UsernamePasswordAuthenticationToken(conta, null, conta.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String recuperarToken(HttpServletRequest request) {
        var authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        return authHeader.substring(7);
    }
}