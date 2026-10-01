# UF NaviGator integration

## What was verified

The user identified `/Users/dinesh/phil/phil-backend` as a reference integration. Source inspection found:

- `src/chat/llm.py:23`: `build_chat_model` builds a `langchain_openai.ChatOpenAI` client for the `navigator` slot.
- `src/chat/llm.py:51`: credential selection uses `NAVIGATOR_API_KEY`, then `LLM_API_KEY`.
- `src/chat/llm.py:52`: base URL selection uses `NAVIGATOR_BASE_URL`, then `LLM_BASE_URL`, then YAML configuration.
- `src/chat/llm.py:56`: model selection uses `NAVIGATOR_LLM_MODEL`, then YAML `model_name`, with `gpt-oss-120b` fallback.
- `config/config.yaml:65`: configured model is `gpt-oss-120b`; the next line uses `https://api.ai.it.ufl.edu` as the root URL.
- `src/common/config.py:16`: PHIL loads its project `.env`. SentinelLLM must load its own secrets independently rather than importing this module.

These are observed configuration/code paths, not evidence of a successful current API call. No `.env` was read, no key was copied, no provider request was sent, and PHIL was not modified.

## Documented endpoint and compatibility

UF's [official quickstart](https://docs.ai.it.ufl.edu/docs/navigator_toolkit/getting_started/quickstart/) documents bearer authentication, OpenAI-compatible clients, and `/v1/chat/completions`. Its [function-calling guide](https://docs.ai.it.ufl.edu/docs/navigator_toolkit/capabilities/function_calling/) uses a `/v1/` API base.

SentinelLLM will normalize a configured origin `https://api.ai.it.ufl.edu` to API base `https://api.ai.it.ufl.edu/v1`. If an operator already supplies `/v1` or `/v1/`, retain a single `/v1`. Append `/chat/completions` exactly once. Reject URLs with credentials, fragments, query strings, unexpected hosts, or unsupported paths. Do not assume the root-base behavior in PHIL proves the endpoint path is correct.

Initial Java integration uses a bounded HTTP client directly; LangChain is not needed just to forward chat. Inspect supported response fields and strip provider-specific/raw error metadata. Test compatibility with the selected model; an OpenAI-compatible interface does not promise identical feature support.

## SentinelLLM configuration contract

| Variable | Default / treatment |
|---|---|
| `SENTINEL_PROVIDER` | `mock`; `navigator` explicitly enables hosted calls |
| `NAVIGATOR_BASE_URL` | `https://api.ai.it.ufl.edu/v1` |
| `NAVIGATOR_LLM_MODEL` | `gpt-oss-120b`, restricted by the application's server-side model allowlist |
| `NAVIGATOR_API_KEY` | Required secret when navigator mode is selected; no committed default |
| `SENTINEL_HOSTED_ENABLED` | `false`; explicit development opt-in |

Use PHIL's primary variable names for familiarity, but do not inherit its legacy `LLM_*` fallbacks in the new project. Multiple ambiguous secret sources make debugging harder. UF documentation calls its sample variable `NAVIGATOR_TOOLKIT_API_KEY`; this is a naming convention, not a distinct authentication protocol. Set SentinelLLM's own `NAVIGATOR_API_KEY` privately during Phase 1 setup.

The user can select another model through trusted configuration and the allowlist. Never hardcode the model into routing or detection rules. Changing the generation model does not change the security policy or confer permissions.

## Cost and permission checks

UF offers local hosted models and team-dependent access/budgets. [UF Toolkit guide](https://it.ufl.edu/ai/navigator-toolkit/), [team access documentation](https://docs.ai.it.ufl.edu/docs/navigator_toolkit/getting_started/request-new-team/).

The key's exact free allowance, allowed models, expiration, rate limits, and potential billable cloud models remain unverified. The user should inspect its portal configuration before the first live smoke test. Do not describe all NaviGator models as unlimited or universally free.

Use synthetic short prompts, a 256-token default, bounded concurrency, no automatic retries, no model-powered policy router, and no hosted calls in default CI/load tests. Generation configuration stays separate from local security detection.

## Phase 1 provider acceptance

- Unit-test root and `/v1` normalization with no network requests.
- Verify mock mode works with no provider key.
- Missing navigator key prevents navigator mode from starting; never silently switch providers.
- Mock upstream verifies correct path, bearer injection, sanitized input, and model selection.
- Reject non-allowlisted model identifiers, redirects, and untrusted destinations.
- Translate upstream 401/403, 429, 5xx, malformed JSON, and timeout into sanitized gateway errors.
- Add an opt-in smoke test only after account allowance is confirmed. Record model/access outcome without logging key, request text, or raw provider error body.

For the large hosted model, this is a remote service integration. There is no plan to run `gpt-oss-120b` on the 16 GiB development machine. Optional Ollama demonstrations will use a much smaller hardware-appropriate model.
