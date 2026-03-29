from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    keycloak_url: str
    keycloak_realm: str
    keycloak_client_id: str
    token_audience: str = "altana-web"

    # Derived URLs — not configured, computed from the base settings
    @property
    def jwks_uri(self) -> str:
        return f"{self.keycloak_url}/realms/{self.keycloak_realm}/protocol/openid-connect/certs"

    @property
    def issuer(self) -> str:
        return f"{self.keycloak_url}/realms/{self.keycloak_realm}"

    # UC6: secret for signing invitation tokens (HS256)
    # In production: rotate this secret regularly and load it from a vault
    invitation_secret: str = "altana-invite-secret-dev-only"

    model_config = {"env_file": [".env", "../.env", "../../.env"]}


settings = Settings()
