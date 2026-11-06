# Clinical review coordinator

This repository contains the `triage-orchestrator-agent` module of the coordinated synthetic clinical review demonstration.

## Get the complete runnable update

Clone the [complete clinical-triage-demo workspace](https://github.com/rsheth8/clinical-triage-demo), which includes all agents, shared contracts, database migrations, fictional data, UI, scripts, tests, numbered UML diagrams and the 23-page presenter guide.

```bash
git clone https://github.com/rsheth8/clinical-triage-demo.git
cd clinical-triage-demo
./scripts/setup.sh
# Start Docker Desktop and Ollama; install the documented local models.
./scripts/start.sh
```

The combined repository is private; `jigsheth57` is the intended collaborator. Accept the invitation or request access from `rsheth8` before cloning. The [front-page guide](https://github.com/rsheth8/clinical-triage-demo/blob/main/docs/START_HERE.md) and [PDF](https://github.com/rsheth8/clinical-triage-demo/blob/main/output/pdf/Clinical_Review_Presenter_Guide.pdf) explain the update and presentation.

## Module build contract

This module is maintained here for source review/history. **The supported full build runs from the combined workspace root.** Java modules inherit `com.healthcare.demo:clinical-triage-demo:1.0.0` and consume shared `demo-contracts`; do not assume a Java module builds from an isolated clone without installing/providing those matching artifacts. The browser can build independently with `npm ci && npm run build`, but the full scenario requires the backend workspace.

The changes are submitted on a review branch; they do not claim that an unmerged default branch has changed. All records/policies are synthetic. No prescriptions, bookings or patient messages are sent. Tanzu artifacts are prepared; an actual foundation is not verified.

Existing `manifest.yaml` files and the original reference PDF, where present, are historical module artifacts. Use the combined workspace's `deploy/tanzu/` templates and current presenter guide for the supported handoff.
