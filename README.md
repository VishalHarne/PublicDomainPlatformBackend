# Public Domain Platform email service

A standalone Spring Boot microservice with three APIs. It sends submissions by SMTP and reads them from the recipient mailbox using IMAP. There is no database, message broker, or service registry.

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
| `SERVER_PORT` | HTTP port, default `8080` |
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

**Security:** The APIs have no authentication. They bind to localhost by default; do not expose them publicly without an authenticated reverse proxy and appropriate access controls. `/getData` returns personal information. Attachments are validated by filename extension only and should be treated as untrusted.

## APIs

All submission data must be a non-empty JSON object. The service preserves the fields provided; define and validate any required business fields in the calling application.

### Submit a grievance

`POST /submitgrievances` with `Content-Type: application/json`:

```json
{"name":"Example","contact":"example@example.com","message":"Please repair the road"}
```

### Submit a resume

`POST /submitResume` as `multipart/form-data`. The `data` part is JSON with content type `application/json`; the optional `file` part accepts a non-empty `.pdf`, `.doc`, or `.docx` file, up to 10 MB.

```powershell
curl.exe -X POST http://localhost:8080/submitResume -F 'data={"name":"Example","email":"example@example.com"};type=application/json' -F 'file=@C:\path\resume.pdf'
```

Omit the `file` part when there is no attachment. Both submission endpoints return `{"id":"...","status":"sent"}` after the SMTP server accepts the message. SMTP acceptance does not guarantee delivery.

### Retrieve submissions

`GET /getData?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z`

`from` is inclusive and `to` is exclusive; both are ISO-8601 instants. The response is a JSON array, newest received first:

```json
[{"type":"resume","receivedAt":"2026-01-02T10:00:00Z","data":{"name":"Example"},"attachments":["resume.pdf"]}]
```

The service reads the configured IMAP folder without marking messages as read, and only returns messages with its submission header. Attachment names are returned, not their contents; open the email to download an attachment. Delivery delays, mailbox rules, deleted messages, or providers stripping custom headers can affect results. Mailbox receipt time, not submission time, determines the period.
