from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    # 지원 크레딧이 특정 조직에 붙어 있을 때만 채운다 (org-...)
    openai_org_id: str = ""
    openai_project_id: str = ""
    whisper_model: str = "whisper-1"
    work_dir: str = "./workdir"
    ocr_lang: str = "korean"

    # 프레임을 몇 초 간격으로 뽑을지 (OCR 기본값)
    default_interval_sec: float = 4.0

    # 영상 길이 상한.
    # 타깃이 20~60분 롱폼 토크·인터뷰라 60분까지 받는다.
    # 이보다 길면 비용과 시간이 감당이 안 된다.
    max_duration_sec: int = 3600

    # OCR 로 인식할 최대 프레임 수.
    #
    # 간격만 고정하면 영상이 길어질수록 프레임이 선형으로 늘어난다.
    # 4초 간격이면 60분 영상에 900장이고, 인식에만 7분 넘게 걸린다.
    # 그래서 상한을 두고, 넘으면 간격을 자동으로 늘린다.
    # 자막은 몇 초씩 유지되므로 간격이 벌어져도 대부분 잡힌다.
    max_ocr_frames: int = 300


@lru_cache
def get_settings() -> Settings:
    return Settings()
