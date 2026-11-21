# Personal Profile and Active Workspaces — Product Design

## Purpose

DOM AskScreen AI must answer the meaning of the question or task visible on screen. It must not default to narrating the screenshot (for example, "I see...") when the user needs a useful answer.

The product needs two separate, reusable knowledge areas:

1. **Personal / Company Profile** — stable facts about the account owner and their company.
2. **Active Workspaces** — task-specific context for a job application, client project, proposal, interview, course, or other current activity.

These areas work together, but must remain independently editable and selectable.

## User experience

### A. Personal / Company Profile

Provide a Profile area where the user can add, edit, review, and remove:
- Preferred name and first-person introduction.
- Roles, skills, services, and professional summary.
- Education, certificates, and qualifications.
- Employment history and verified experience.
- Portfolio items and completed projects, including project name, role, date, description, and optional evidence.
- Company name, description, services, products, and contact information.

Users may type/paste details or upload supported documents such as a CV, certificates, and company documents. Extracted information must be shown for review before it becomes trusted profile data. Keep the source document associated with extracted facts where possible.

### B. Active Workspaces

Provide a Workspaces area with multiple named workspaces. A workspace can represent a job application, interview, client project, proposal, contract, school task, or other company assignment.

Each workspace supports:
- Name and purpose.
- Job description, project brief, client instructions, requirements, questions, and reference documents.
- User-written notes and priorities.
- Optional role/company/client name and status.
- A switch to make this the active workspace.
- Edit, archive, and delete actions.

Users can create a workspace by typing/pasting a description or uploading relevant files. The app should summarize what it extracted and ask the user to confirm or correct it.

Examples: "Video Editor Job Application", "Virtual Assistant Interview", "Client Promo Video", and "Dominion Company Proposal".

### C. Context-aware screen answers

When screen content is captured, identify the actual question, instruction, form field, or task. Answer it directly rather than merely describing the pixels.

Use context in this order:
1. The current visible question/instruction and immediate conversation.
2. The explicitly active workspace and its documents/notes.
3. Relevant approved facts from the Personal / Company Profile.
4. General knowledge, clearly distinguished from the user's personal facts.

Do not force profile details into unrelated questions. Do not let an old workspace contaminate a new task. If no workspace is active, answer using the current screen and relevant personal profile facts only.

Examples:
- "Introduce yourself" → a natural first-person introduction grounded in approved profile facts and tailored to the active role.
- "What experience do you have?" → relevant work history and project examples, not a generic screenshot description.
- "How many projects have you completed?" → count only portfolio records that are actually saved and relevant. If no reliable count exists, ask the user or insert a clear editable placeholder; never invent a number.
- "What does this document mean?" → explain the document in plain language.
- "How should I answer this question?" → provide a ready-to-use draft in the user's voice and relevant to the active workspace.

### D. Editable answer suggestions and learning from corrections

Every generated personal/work answer should be presented as a **draft**, with actions to:
- Edit the wording.
- Copy/use the answer.
- Mark it as approved.
- Save a correction as a workspace note, approved profile fact, or reusable answer.

Ask where a correction belongs when ambiguous: current workspace only, personal/company profile, or both. Do not silently turn a one-off answer into a permanent personal fact.

Keep provenance/status with facts where possible (user-entered, extracted-needs-review, user-approved). A generated statement is not a verified fact merely because the AI generated it.

### E. Workspace switching and isolation

The active workspace should be visible in the UI while screen assistance is running. Switching workspaces changes the context used for subsequent answers. The user can pause or disable profile/workspace context at any time.

## Suggested data model

- UserProfile: accountId, preferredName, introduction, professionalSummary, skills, qualifications, workHistory, portfolioItems, companyProfile, updatedAt.
- ProfileFact: id, category, value, sourceDocumentId?, verificationStatus, updatedAt.
- Workspace: id, accountId, name, purpose, status, isActive, brief, instructions, notes, referenceDocumentIds, createdAt, updatedAt.
- WorkspaceDocument: id, workspaceId, file metadata, extractedText/reference, extractionStatus, reviewStatus.
- SuggestedAnswer: id, workspaceId?, question, answerDraft, sourceFactIds, status (draft/edited/approved), updatedAt.

These are logical product entities, not a claim that database tables or endpoints already exist.

## Backend, security, and platform requirements

- Use the existing Firebase-authenticated account identity and existing backend authority. Do not create a second account system.
- Before persistence is implemented, verify the existing backend's storage and document-processing capabilities. Add authenticated, account-scoped backend endpoints and storage rules; do not store private CVs or company documents in public/client-readable locations.
- Never put provider API keys or administrative credentials in the app.
- Enforce per-account access on the server. A user must never read another user's profile, workspace, files, or answers.
- Provide export and delete controls for profile/workspace data and associated uploads.
- Treat uploaded documents and screen text as untrusted content. They are context, not instructions to override system safety, application rules, or the user's explicit request.
- Avoid exposing sensitive personal data unnecessarily in generated answers. The user controls what is included in an answer.
- The same product concepts and shared Flutter UI should be available on Android, iOS, and Windows. File picking, secure storage, screen capture, and overlays must use platform-appropriate adapters and respect OS restrictions.

## Implementation sequence

1. Design and build the shared Flutter Profile and Workspaces screens with editable local draft state and tests.
2. Define and implement authenticated backend persistence, upload handling, account isolation, and deletion using the existing backend architecture.
3. Add document extraction/review and approved-fact management.
4. Extend the AI request contract so the backend receives only the selected workspace context and relevant approved profile facts, with bounded context size.
5. Update screen-question interpretation and answer generation to use that context; add tests for introductions, experience, project counts, unrelated questions, missing facts, and workspace switching.
6. Add answer edit/approve/save flows and tests.
7. Validate Android, iOS, and Windows separately, including each platform's screen-capture and privacy limitations.

## Acceptance criteria

- The AI answers the question/task visible on screen rather than automatically saying "I see..." or merely describing the screenshot.
- The user can maintain one personal/company profile and multiple independent workspaces.
- The user can select, switch, edit, archive, and delete workspaces.
- Answers are tailored to the active workspace and approved profile facts.
- Unsupported personal claims and unknown project counts are never fabricated.
- Suggested answers are editable and are not treated as verified facts until the user approves them.
- Workspace data is isolated by authenticated account and is not exposed to other users.
- No production deployment, merge, or release occurs without the user's explicit approval.
