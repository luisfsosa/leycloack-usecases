from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    keycloak_url: str
    keycloak_realm: str
    keycloak_client_id: str
    token_audience: str = "altana-web"

    # URLs derivadas — no se configuran, se calculan
    @property
    def jwks_uri(self) -> str:
        return f"{self.keycloak_url}/realms/{self.keycloak_realm}/protocol/openid-connect/certs"

    @property
    def issuer(self) -> str:
        return f"{self.keycloak_url}/realms/{self.keycloak_realm}"

    model_config = {"env_file": [".env", "../.env", "../../.env"]}


settings = Settings()
