-- Run once on installations created with the original submissions schema.
-- Historical applications did not retain a phone snapshot. This backfill records
-- the person's current number, not necessarily the number submitted at the time.
BEGIN;
ALTER TABLE public.candidate_applications ADD COLUMN submitted_phone text;
UPDATE public.candidate_applications AS a
SET submitted_phone = p.mobile_e164
FROM public.people AS p
WHERE p.person_id = a.person_id;
-- Fails rather than inventing a phone when a historical person has no number.
ALTER TABLE public.candidate_applications
  ALTER COLUMN submitted_phone SET NOT NULL,
  ADD CONSTRAINT candidate_phone_format CHECK (submitted_phone ~ '^\+[1-9][0-9]{7,14}$');
COMMIT;
