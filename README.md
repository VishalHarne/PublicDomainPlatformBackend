# Public Domain Platform email service

A standalone Spring Boot microservice. It supports email submissions and IMAP retrieval, as well as separate PostgreSQL-backed submission APIs.

## Local ports

| Service | Local URL | Configuration |
|---|---|---|
| Website | `http://localhost:8080` | Configured in the separate website project |
| Email backend and APIs | `http://localhost:8081` | `SERVER_PORT` in this project's `.env` |

The backend permits the website origin through `CORS_ALLOWED_ORIGINS` in `.env`. If either port changes, update the website's backend URL and the backend's allowed origins as appropriate. Changes to `.env` require a backend restart.

## Requirements and configuration

Use Java 17 or newer and Maven 3.6.3 or newer. Settings are read from a `.env` file in the working directory (the project root when running locally), or from environment variables, which take precedence. Copy `.env.example` to `.env` and fill in the values; `.env` is git-ignored and must never be committed. The variables are:

| Variable | Description |
|---|---|
| `SMTP_HOST`, `SMTP_PORT` | SMTP server and port (port defaults to `587`; STARTTLS is required) |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | SMTP login |
| `MAIL_FROM` | Sender address permitted by the SMTP provider |
| `MAIL_RECIPIENT` | Destination email address |
| `IMAP_HOST`, `IMAP_PORT` | IMAP server and TLS port (defaults to `993`) |
| `IMAP_USERNAME`, `IMAP_PASSWORD` | Login for the mailbox that receives `MAIL_RECIPIENT` |
| `IMAP_FOLDER` | Folder to read, default `INBOX` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated website origins allowed to call the APIs from a browser, default `http://localhost:3000,http://localhost:5173,http://localhost:8080` |
| `SERVER_PORT` | HTTP port, default `8081` |
| `SERVER_ADDRESS` | Listening address, default `127.0.0.1` |

Use provider-specific app passwords if required, and enable SMTP and IMAP for the account. The application uses password-based authentication, not OAuth. Credentials belong in environment variables, not source control.

Run `mvn spring-boot:run`, or build with `mvn package` and run `java -jar target\email-service-1.0.0.jar`.

### Send a real test email

`RealGrievanceEmailTest` calls the grievance endpoint with the real mail service and sends a dummy message to the configured recipient. It is skipped unless explicitly enabled, so normal test runs do not send email. Configure the SMTP settings and recipient in `.env`, then run in PowerShell:

```powershell
$env:SEND_REAL_TEST_EMAIL = "true"
mvn "-Dtest=RealGrievanceEmailTest" test
Remove-Item Env:\SEND_REAL_TEST_EMAIL
```

In IntelliJ, add `SEND_REAL_TEST_EMAIL=true` to the test run configuration's environment variables (the working directory must be the project root so `.env` is found) and run `RealGrievanceEmailTest`. A passing test confirms SMTP acceptance, not delivery; check the recipient inbox and spam folder. The test does not require IMAP credentials.

**Security:** Local mode is intended for localhost only. It has no authentication and `/getData` returns personal information. The Render profile disables retrieval and requires Turnstile for submissions, but this is not a substitute for rate limiting or attachment scanning. Attachments are validated by filename extension only and should be treated as untrusted.

## Deploy to Render

The included `render.yaml` and `Dockerfile` deploy the service as a **paid Starter** web service. Render free web services block outbound SMTP ports 25, 465 and 587, so the existing SMTP sender requires a paid instance. Check Render's current pricing before deploying.

1. Push the repository to GitHub, then create a Render Blueprint from the repository. Render will build the Docker image and start it with the `render` Spring profile.
2. Provide `SMTP_HOST`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM`, and `MAIL_RECIPIENT` in Render's environment settings. Use a provider-specific app password if needed. Do not commit credentials or upload `.env`; the Docker build excludes it. `SMTP_PORT` defaults to 587 with required STARTTLS. IMAP settings are not needed on Render because retrieval is disabled.
3. `CORS_ALLOWED_ORIGINS` in `render.yaml` allows `https://omraje.in`, `https://www.omraje.in`, and the GitHub Pages site at `https://vishalharne.github.io/lovable-OmRaje/`. For CORS, the GitHub Pages entry is the origin `https://vishalharne.github.io`; origin values must not include paths or trailing slashes. If the website uses additional origins, add their exact HTTPS origins there, separated by commas. CORS is not authentication.
4. Create a [Cloudflare Turnstile widget](https://developers.cloudflare.com/turnstile/get-started/) for the website's hostname. Set its **secret key** as `TURNSTILE_SECRET_KEY` in Render; keep it private. Add the public site key and widget to each website submission form.
5. Configure the website's backend URL to the Render HTTPS URL. Send the freshly generated widget token in the `X-Turnstile-Token` header with each request to `/submitgrievances` or `/submitResume`. Reset the widget and obtain a new token after each submission attempt; tokens expire and are single-use. The backend returns 400 for a missing token, 403 for a failed verification, and 503 when verification is unavailable. These requests will fail until the website is updated.

Render supplies `PORT`; the Render profile listens on `0.0.0.0` and uses that port. `/health` is a liveness check only; it does not verify SMTP connectivity or delivery. `/getData` is disabled on Render; use the recipient mailbox to access submissions. Do not enable it publicly without implementing proper authentication and authorization.

Add request rate limits and abuse monitoring at a trusted edge or gateway before public launch. Turnstile reduces automated abuse but does not prevent all spam or resource exhaustion. Apply mail-provider sending limits and handle received attachments as untrusted; no malware scanning is included.

Local mode does not require Turnstile. To test it locally, set `app.turnstile.enabled=true` as a Spring property and supply `TURNSTILE_SECRET_KEY`; never use production secrets in source control.

## APIs

### Database submission confirmations

`POST /api/grievances` and `POST /api/applications` send a confirmation using the configured SMTP account after the database transaction commits. The email contains the database-generated grievance or application ID. Grievances use `contactValue` when `contactType` is `email`; applications use the optional `email` field. Submissions without an email address are still accepted.

The JSON response includes `id`, `status`, and `emailStatus`: `sent` means the SMTP server accepted the confirmation (not that it was delivered), `failed` means SMTP acceptance could not be confirmed, and `not_requested` means no email was provided. A failed email does not undo the saved submission; retain the returned ID rather than resubmitting. Confirmation emails are not retried automatically, and a timeout can leave delivery uncertain.

### Email submissions

All email submission data must be a non-empty JSON object. The service preserves the fields provided; define and validate any required business fields in the calling application.
On Render, both email submission endpoints additionally require a fresh `X-Turnstile-Token` header.

### Submit a grievance

`POST /submitgrievances` with `Content-Type: application/json`:

```json
{"name":"Example","contact":"example@example.com","message":"Please repair the road"}
```

### Submit a resume

`POST /submitResume` as `multipart/form-data`. The `data` part is JSON with content type `application/json`; the optional `file` part accepts a non-empty `.pdf`, `.doc`, or `.docx` file, up to 10 MB.

```powershell
curl.exe -X POST http://localhost:8081/submitResume -F 'data={"name":"Example","email":"example@example.com"};type=application/json' -F 'file=@C:\path\resume.pdf'
```

Omit the `file` part when there is no attachment. Both submission endpoints return `{"id":"...","status":"sent"}` after the SMTP server accepts the message. SMTP acceptance does not guarantee delivery.

### Retrieve submissions

Available in local mode only; disabled by the Render profile.

`GET /getData?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z`

`from` is inclusive and `to` is exclusive; both are ISO-8601 instants. The response is a JSON array, newest received first:

```json
[{"type":"resume","receivedAt":"2026-01-02T10:00:00Z","data":{"name":"Example"},"attachments":["resume.pdf"]}]
```

The service reads the configured IMAP folder without marking messages as read, and only returns messages with its submission header. Attachment names are returned, not their contents; open the email to download an attachment. Delivery delays, mailbox rules, deleted messages, or providers stripping custom headers can affect results. Mailbox receipt time, not submission time, determines the period.
