package org.argeo.security.jackrabbit;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.jackrabbit.core.security.authentication.AuthContext;

/** Wraps a regular {@link LoginContext}, using the proper class loader. */
class ArgeoAuthContext implements AuthContext {
	private LoginContext lc;

	private String loginContextName;

	public ArgeoAuthContext(String appName, Subject subject, CallbackHandler callbackHandler) {
		this.loginContextName = appName;
		// Context class loader for login context is set when it is created.
		// we make sure that it uses our won class loader
		ClassLoader currentContextCl = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(SystemJackrabbitLoginModule.class.getClassLoader());
			lc = new LoginContext(loginContextName, subject, callbackHandler);
		} catch (LoginException e) {
			throw new IllegalStateException("Cannot configure Jackrabbit login context", e);
		} finally {
			Thread.currentThread().setContextClassLoader(currentContextCl);
		}
	}

	@Override
	public void login() throws LoginException {
		try {
			lc.login();
		} catch (LoginException e) {
			// we force a runtime exception since Jackrabbit swallows LoginException
			// and still create a session
			throw new IllegalStateException("Login context " + loginContextName + " failed", e);
		}
	}

	@Override
	public Subject getSubject() {
		return lc.getSubject();
	}

	@Override
	public void logout() throws LoginException {
		lc.logout();
	}

}
