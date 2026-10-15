from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    # 지원 크레딧이 특정 조직에 붙어 있을 때만 채운다 (org-...)
    openai_org_id: str = ""
    openai_project_id: str = ""
    whisper_model: str = "whisper-1"

    # 음성 인식을 어디서 할지. (2026-10 고도화)
    #   groq   Groq 의 Whisper large-v3 (기본). OpenAI whisper-1 이 2027-02-26 에 종료돼서 옮겼다.
    #          OpenAI 와 같은 형식의 API 라 문장별 시각(segment)을 그대로 받는다. 분당 약 $0.0019.
    #   openai OpenAI whisper-1 (예전 방식). 분당 $0.006, OpenAI 크레딧에서 차감.
    # groq 로 두었는데 GROQ_API_KEY 가 없거나 Groq 호출이 실패하면 OpenAI 키로 대신 돌린다.
    stt_provider: str = "groq"
    groq_api_key: str = ""
    groq_whisper_model: str = "whisper-large-v3"
    groq_base_url: str = "https://api.groq.com/openai/v1"
    # 음성 언어 힌트 (ISO-639-1, 예: ko). 비우면 모델이 알아서 판별한다.
    stt_language: str = ""
    work_dir: str = "./workdir"
    ocr_lang: str = "korean"

    # 프레임을 몇 초 간격으로 뽑을지 (OCR 기본값)
    default_interval_sec: float = 4.0

    # 영상 길이 상한.
    # 타깃이 20~60분 롱폼 토크·인터뷰인데, 실제로는 70~80분짜리도 흔해서
    # 90분까지 받는다.
    #
    # 다만 긴 영상은 비용과 시간이 크게 늘어난다.
    #   60분: 음성 인식 약 500원 + AI 분석 40~60원 = 약 550원. 대본 300줄 이상 → LLM 호출 수십 번
    #   90분: 그보다 1.5배
    # 요청 한도가 낮은 계정에서는 중간에 끊길 수 있다.
    # 시연은 3~5분짜리로 하는 것을 권한다.
    max_duration_sec: int = 5400

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
