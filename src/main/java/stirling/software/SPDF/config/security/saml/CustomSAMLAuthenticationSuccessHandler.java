package stirling.software.SPDF.config.security.saml;

import java.io.IOException;

import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.SavedRequest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import stirling.software.SPDF.config.security.LoginAttemptService;
import stirling.software.SPDF.config.security.UserService;
import stirling.software.SPDF.model.ApplicationProperties;
import stirling.software.SPDF.model.ApplicationProperties.Security.OAUTH2;
import stirling.software.SPDF.model.AuthenticationType;
import stirling.software.SPDF.utils.RequestUriUtils;

@Slf4j
public class CustomSAMLAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private LoginAttemptService loginAttemptService;
    private UserService userService;
    private ApplicationProperties applicationProperties;

    public CustomSAMLAuthenticationSuccessHandler(
            LoginAttemptService loginAttemptService,
            UserService userService,
            ApplicationProperties applicationProperties) {
        this.loginAttemptService = loginAttemptService;
        this.userService = userService;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws ServletException, IOException {

        Object principal = authentication.getPrincipal();
        String username = "";

        if (principal instanceof OAuth2User) {
            OAuth2User oauthUser = (OAuth2User) principal;
            username = oauthUser.getName();
        } else if (principal instanceof UserDetails) {
            UserDetails oauthUser = (UserDetails) principal;
            username = oauthUser.getUsername();
        } else if (principal instanceof ScimSaml2AuthenticatedPrincipal) {
            ScimSaml2AuthenticatedPrincipal samlPrincipal =
                    (ScimSaml2AuthenticatedPrincipal) principal;
            username = samlPrincipal.getName();
        }

        // Get the saved request
        HttpSession session = request.getSession(false);
        String contextPath = request.getContextPath();
        SavedRequest savedRequest =
                (session != null)
                        ? (SavedRequest) session.getAttribute("SPRING_SECURITY_SAVED_REQUEST")
                        : null;

        if (savedRequest != null
                && !RequestUriUtils.isStaticResource(contextPath, savedRequest.getRedirectUrl())) {
            // Redirect to the original destination
            super.onAuthenticationSuccess(request, response, authentication);
        } else {
            OAUTH2 oAuth = applicationProperties.getSecurity().getOauth2();

            if (loginAttemptService.isBlocked(username)) {
                if (session != null) {
                    session.removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
                }
                throw new LockedException(
                        "Your account has been locked due to too many failed login attempts.");
            }
            if (userService.usernameExistsIgnoreCase(username)
                    && userService.hasPassword(username)
                    && !userService.isAuthenticationTypeByUsername(
                            username, AuthenticationType.OAUTH2)
                    && oAuth.getAutoCreateUser()) {
                response.sendRedirect(contextPath + "/logout?oauth2AuthenticationErrorWeb=true");
                return;
            }
            try {
                if (oAuth.getBlockRegistration()
                        && !userService.usernameExistsIgnoreCase(username)) {
                    response.sendRedirect(contextPath + "/logout?oauth2_admin_blocked_user=true");
                    return;
                }
                if (principal instanceof OAuth2User) {
                    userService.processOAuth2PostLogin(username, oAuth.getAutoCreateUser());
                }
                response.sendRedirect(contextPath + "/");
                return;
            } catch (IllegalArgumentException e) {
                response.sendRedirect(contextPath + "/logout?invalidUsername=true");
                return;
            }
        }
    }
}
