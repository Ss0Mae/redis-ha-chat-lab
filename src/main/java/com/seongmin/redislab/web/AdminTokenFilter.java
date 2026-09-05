package com.seongmin.redislab.web;

import com.seongmin.redislab.config.LabProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 실험 API 의 쓰기 요청은 X-Lab-Admin-Token 이 있어야 한다. 조회·워크로드·채팅 API 는 열려 있다. */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

	private final String token;

	public AdminTokenFilter(LabProperties lab) { this.token = lab.adminToken(); }

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		if ("POST".equals(req.getMethod()) && req.getRequestURI().startsWith("/api/experiments") && !token.equals(req.getHeader("X-Lab-Admin-Token"))) {
			res.setStatus(403);
			res.setContentType("application/json");
			res.getWriter().write("{\"error\":\"X-Lab-Admin-Token required\"}");
			return;
		}
		chain.doFilter(req, res);
	}
}
