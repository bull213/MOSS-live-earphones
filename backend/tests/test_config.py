from app.config import Settings


def test_dotenv_and_environment_precedence(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text("OPENAI_API_KEY=local-test-only-key\nOPENAI_MODEL=file-model\n", encoding="utf-8")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.setenv("OPENAI_MODEL", "environment-model")
    settings = Settings(_env_file=env_file)
    assert settings.openai_api_key.get_secret_value() == "local-test-only-key"
    assert settings.openai_model == "environment-model"
    assert "local-test-only-key" not in repr(settings)


def test_empty_key_is_unconfigured():
    settings = Settings(_env_file=None, openai_api_key=" \t ")
    assert settings.openai_api_key is None
