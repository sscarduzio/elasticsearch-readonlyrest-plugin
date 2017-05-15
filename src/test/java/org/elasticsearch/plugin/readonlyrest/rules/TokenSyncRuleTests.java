package org.elasticsearch.plugin.readonlyrest.rules;

import java.util.Calendar;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.TokenSyncRule;
import org.elasticsearch.plugin.readonlyrest.oauth.OAuthToken;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.junit.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import junit.framework.TestCase;

public class TokenSyncRuleTests extends TestCase {
	private OAuthToken expiredToken;
	private OAuthToken validToken;
	
	protected void setUp() throws Exception {
		super.setUp();
		expiredToken = new OAuthToken();
		Calendar c = Calendar.getInstance();
		c.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY) -1);
		expiredToken.setExp(c.getTime());
		expiredToken.setAlg("test");
		validToken = new OAuthToken();
		c.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY) + 2);
		validToken.setExp(c.getTime());
		validToken.setAlg("test");
	}
	
	private RuleExitResult match(String configured, RequestContext rc) throws RuleNotConfiguredException {
		SyncRule r = new TokenSyncRule(Settings.builder().put("token", configured).build());
		return r.match(rc);
	}
	
	@Test
	public void testExpired() throws RuleNotConfiguredException {
		RequestContext rc = Mockito.mock(RequestContext.class);
		when(rc.getToken()).thenReturn(expiredToken);
		RuleExitResult res = match("true", rc);
		assertFalse(res.isMatch());
	}
	
	@Test
	public void testValid() throws RuleNotConfiguredException {
		RequestContext rc = Mockito.mock(RequestContext.class);
		when(rc.getToken()).thenReturn(validToken);
		RuleExitResult res = match("true", rc);
		assertTrue(res.isMatch());
	}
	
	@Test
	public void testNoToken() throws RuleNotConfiguredException {
		RequestContext rc = Mockito.mock(RequestContext.class);
		when(rc.getToken()).thenReturn(null);
		RuleExitResult res = match("false", rc);
		assertTrue(res.isMatch());
	}
	
	@Test
	public void testBypassToken() throws RuleNotConfiguredException {
		RequestContext rc = Mockito.mock(RequestContext.class);
		when(rc.getToken()).thenReturn(expiredToken);
		RuleExitResult res = match("false", rc);
		assertFalse(res.isMatch());
	}

}
