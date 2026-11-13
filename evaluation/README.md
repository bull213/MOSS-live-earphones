# Visual assistant evaluation cases

`case-catalog.csv` is a set of 25 **planned cases**, not a record of model
performance. No images, expected button locations, model responses or task
outcomes have been supplied. `fixture_status=not_collected` applies to every
catalog row until a separate private working record is prepared.

## Prepare a case

1. Choose your own non-sensitive photograph or screenshot. Do not use another
   person's media without permission. Store it in the ignored
   `evaluation/fixtures/private/` directory, not in the repository.
2. Check that the photo matches the row's **required visible evidence**. In a
   private working note, record exactly what is visible: relevant labels,
   controls, screen state and any uncertainty. Do this before asking the model.
   If the photo does not match, collect a different photo or mark the case
   invalid; do not invent labels or an expected action to fill the gap.
3. If using an intentionally unclear image, first confirm that the information
   really is unreadable or missing. Keep any clear reference image private too.
4. Do not test physical actions on equipment that is unsafe or unsuitable. The
   smoke case evaluates safe advice for a reported situation; it does not ask
   anyone to produce smoke or create a hazard.

## Run and record

When real model access is available, copy `acceptance-template.csv` to a new file
under the ignored `evaluation/results/` directory. Keep its column names. Replace
the example rows with one row per attempt using the catalog's `case_id`, question
and pass criterion as `expected_behavior`. Put the pre-recorded visible evidence,
photo reference, model/prompt version and other observations in the private
`notes` field. Do not put photograph bytes or credentials in the CSV.

Start attempts as `not_run`. After an actual response, record the real answer,
request ID and timing, then set status to `pass` or `fail` based on the row's
criterion and the visible evidence. A pass requires zero unsupported instructions;
if the number was not assessed, leave it blank and do not mark a pass. Use `yes`,
`no` or `not_applicable` for correctness and clarification appropriateness.

Record `task_completed` only if someone actually performed and verified the
physical action; otherwise use `not_observed`. Count interactions actually made,
not predicted. Record `client_total_ms` from tapping send until the response is
displayed and use the returned `server_total_ms` and `model_ms` separately;
do not invent missing timings. Keep each retry as a separate row. A single-turn
case does not establish multi-step task completion.

The initial live-API acceptance still needs the three cases documented in the
root README. The larger catalog supports broader evaluation after that gate; it
does not replace it or make offline unit testing an AI accuracy test.
