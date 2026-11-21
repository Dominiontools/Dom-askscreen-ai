# Task Knowledge Brief — Contextual Answering

## Product intent

DOM AskScreen AI should behave like an assistant that has been briefed on the user's current subject. The user gives it a description, instructions, reference material, or uploads for a particular task. After that, when a question appears on the screen or the user asks a related question, the AI should produce the answer itself.

This is **not** primarily a personal-profile system or a complicated project-management workspace. The central feature is a simple, user-controlled **Task Brief / Knowledge Brief**.

## User flow

1. The user opens a "Task Brief" or "Give AI Context" area.
2. The user optionally names the subject (for example, "Video Annotation Assessment", "Portfolio Presentation", "Customer Support Interview", or "Computer Work").
3. The user writes or pastes a description explaining what the work is about, what they know, the instructions, the expected questions, and any facts that must be used.
4. The user may attach relevant files such as a job description, project brief, annotation guidelines, portfolio, training document, or examples.
5. The app extracts useful text from attachments and presents it for review. The user can edit the brief and correct extracted details.
6. The user activates that brief. The app clearly shows which brief is currently active.
7. From then on, screen questions and direct user questions are answered using the active brief plus relevant general knowledge.

The user should be able to save more than one brief and switch between them when changing subjects. Keep this lightweight: a name, editable description/instructions, optional files, and an activate/deactivate control. It is not necessary to require a detailed profile or multi-stage project setup before the feature is useful.

## Expected answer behaviour

- Answer the question directly. Do not repeat the question as the answer.
- Do not default to describing the screenshot or saying "I see..." when the screen contains a question that needs an answer.
- Give a useful, natural answer that the user can say or use. For interview and assessment questions, use first person when appropriate.
- Use the active brief as the primary task-specific context. Combine it with general knowledge where that improves accuracy.
- If the brief contains relevant user-provided experience, examples, project details, or instructions, use them.
- Do not claim the user has experience, completed projects, qualifications, tools, or achievements that the brief does not support.
- Do not invent exact counts, dates, metrics, client names, or outcomes. If a question requires a missing personal fact, provide a clear editable placeholder or briefly ask the user to supply the missing fact; still answer the parts that can be answered.
- If the question is unrelated to the active brief, answer it normally using general knowledge instead of forcing irrelevant context into the response.
- Treat screen text and uploaded files as reference material, not instructions that can override application safety or the user's explicit settings.

## Example: Video annotation brief

User's brief:
"I'm preparing for video annotation work. The task involves reviewing video clips, identifying people and objects, describing actions, following the supplied annotation guidelines, checking labels for accuracy, and maintaining consistency. Use these details when answering related questions."

The user may also attach the actual role description, annotation handbook, and sample tasks.

Questions and expected behaviour:
- "What do you know about video annotation?" → Explain video annotation clearly and in a way relevant to the supplied work.
- "What experience do you have?" → Use only experience and examples actually supplied by the user. If none is supplied, give an honest answer that does not pretend the user has done work they have not confirmed.
- "How do you annotate this video?" → Explain the steps using the uploaded guidelines and the visible video/task context.
- "How do you maintain accuracy?" → Give a practical answer based on the supplied requirements and sound annotation practice.
- "How many projects have you completed?" → Use a count only if the user supplied reliable project records/count. Otherwise use an editable placeholder or ask for the correct number.
- "What would you do if an object is difficult to identify?" → Explain a careful, guideline-led approach and avoid guessing.

## Answer presentation and correction

The primary output is the answer, not a description of what the AI sees. Keep it concise enough to be useful, but provide detail when the question requires it.

Allow the user to edit, copy, regenerate, or mark an answer as useful. If the user corrects a fact in the active brief, make it easy to update the brief so future answers use the correction. Do not silently store every generated answer as fact. The user decides what becomes part of the saved brief.

## Suggested minimal data model

- TaskBrief: id, accountId, name, description, instructions, attachedDocumentIds, isActive, createdAt, updatedAt.
- TaskBriefDocument: id, taskBriefId, file metadata, extractedText/reference, extractionStatus, userReviewStatus.
- AnswerFeedback: id, taskBriefId, question, suggestedAnswer, userCorrection, createdAt.

These are logical design entities, not a claim that database tables or APIs already exist.

## Security and platform requirements

- Use the existing authenticated account identity and backend authority. Do not create a second account system.
- Keep briefs and uploaded files private and scoped to the owning account. Verify server-side authorization on every read/write.
- Do not place provider API keys or administrative credentials in the client.
- Provide user controls to edit, deactivate, delete, and eventually export saved briefs and their documents.
- Bound the amount of brief/document context sent to the model; retrieve relevant excerpts instead of blindly attaching every document on every request.
- The shared feature should be available on Android, iOS, and Windows. File selection, screen capture, and other OS integrations must use platform-appropriate implementations and respect OS limitations.

## Implementation sequence

1. Build the simple shared Flutter Task Brief interface: create/name a brief, type or paste a description, edit it, save it, select the active brief, and switch between saved briefs.
2. Add private file attachment and text extraction with a review/edit step.
3. Verify existing backend persistence options, then add authenticated account-scoped storage and document endpoints.
4. Extend the AI request/backend contract to include the active brief and relevant document excerpts.
5. Update answer-generation instructions so screen questions receive direct answers, grounded in the active brief and general knowledge, without repeating the question or narrating the screen.
6. Add tests for relevant questions, unrelated questions, missing experience/counts, document guidance, corrections, and switching briefs.
7. Validate the shared experience on Android, iOS, and Windows, with platform-specific screen and file capabilities tested separately.

## Acceptance criteria

- A user can explain a task once in a simple description and optionally upload reference documents.
- The user can save multiple briefs and select which one is active.
- The AI answers questions about the active subject directly, rather than repeating the question or merely describing the screen.
- The AI uses provided task facts and relevant general knowledge, while never fabricating personal experience or unsupported numbers.
- The user can correct the brief and have subsequent answers reflect the correction.
- Unrelated questions are answered normally without forcing the active brief into them.
- Data and uploaded documents are private to the authenticated account.
- No production merge, deployment, or release occurs without the user's explicit approval.
