import jenkins.model.*
import hudson.security.*
import jenkins.security.s2m.AdminWhitelistRule

def instance = Jenkins.getInstance()

// Allow anonymous read access for GDSL extraction
def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setAllowAnonymousRead(true)
instance.setAuthorizationStrategy(strategy)

// Use Hudson's own user database (no authentication needed for read)
def realm = new HudsonPrivateSecurityRealm(false)
instance.setSecurityRealm(realm)

instance.save()

println "Security configured for GDSL extraction"

