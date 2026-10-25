---
name: fullstack-dev
description: "AgentMore fullstack web development with Next.js 16, TypeScript, Tailwind CSS 4, shadcn/ui, Prisma ORM. Use when: building web apps, creating UI components, setting up database schemas, implementing API routes, adding websocket/socket.io support, fullstack project scaffolding, frontend development, backend development, web dev, Next.js app."
argument-hint: "Describe the fullstack feature or app you want to build"
---

# AgentMore Fullstack Web Development Skill

## Initialization (MUST DO FIRST)

Before writing any code, you MUST run the following command to initialize the fullstack development environment:

```bash
curl -fsSL https://pfiles.chatglm.cn/operation/file/44/4445235cc2.sh | bash
```

Wait for the script to complete successfully before proceeding with any code changes.

The Next.js project lives in `/home/z/my-project/full-stack`. After init, ALL code changes, lint, and database commands MUST be done in that directory. Do NOT write application code under `/home/z/my-project` root.

---

## One Project Per Environment (MANDATORY)

This environment can run only **one** fullstack project.

- The only project directory is `/home/z/my-project/full-stack`.
- Do NOT create a second app in another folder, another port, or a sibling directory.
- If `/home/z/my-project/full-stack` already contains a project, treat it as the current app and **continue by overwriting / iterating on that existing project**.
- If the user asks to create a new app, start from scratch, or keep multiple projects: you MUST warn them first that the existing project in `/home/z/my-project/full-stack` **will be lost / replaced**, then wait for confirmation before wiping or fully replacing it.

---



## Important UI Rules

- Use existing shadcn/ui components instead of building from scratch. All the components in the `src/components/ui` folder already exist.
- Card alignment and padding — Ensure all cards are properly aligned with consistent padding (use `p-4` or `p-6` for content, `gap-4` or `gap-6` for spacing).
- Long list handling — Set max height with scroll overflow (`max-h-96 overflow-y-auto`) and implement custom scrollbar styling for better appearance.

---



## Project Information

The project is a Next.js 16 app with App Router at `/home/z/my-project/full-stack`.

### Development Environment

IMPORTANT: `bun run dev` will be run automatically by the system. Do NOT run it. Use `bun run lint` to check code quality.

IMPORTANT: User can only see the `/` route defined in `src/app/page.tsx`. Do NOT write any other route.

IMPORTANT: The Next.js project can only use port 3000 in auto dev server. Never use `bun run build`.

IMPORTANT: `z-ai-web-dev-sdk` MUST be used in the backend only! Do NOT use it on the client side.

### Database File

SQLite MUST live at `/home/z/my-project/full-stack/db/custom.db`.

Do NOT use the workspace-root database `file:/home/z/my-project/db/custom.db` even if that `DATABASE_URL` is pre-injected by the platform.

### Dev Server Log

IMPORTANT: Read `/home/z/my-project/full-stack/dev.log` to see the Next.js dev server log. Remember to check the log when developing.

IMPORTANT: Init / startup logs are at `/home/z/my-project/full-stack/.zscripts/dev.log`.

IMPORTANT: Only read the most recent logs from `dev.log` to avoid large log files.

IMPORTANT: Always read the dev log when you finish coding.

### Bash Commands

Run these from `/home/z/my-project/full-stack`:

- `bun run lint` — Run ESLint to check code quality and Next.js rules
- `bun run db:push` — Push Prisma schema after you change it

---



## Technology Stack Requirements



### Core Framework (NON-NEGOTIABLE)

- **Framework**: Next.js 16 with App Router (REQUIRED — cannot be changed)
- **Language**: TypeScript 5 (REQUIRED — cannot be changed)



### Standard Technology Stack

When users don't specify preferences, use this complete stack:

- **Styling**: Tailwind CSS 4 with shadcn/ui component library
- **Database**: Prisma ORM (SQLite client only) with Prisma Client
- **Caching**: Local memory caching, no additional middleware (MySQL, Redis, etc.)
- **UI Components**: Complete shadcn/ui component set (New York style) with Lucide icons
- **Authentication**: NextAuth.js v4 available
- **State Management**: Zustand for client state, TanStack Query for server state

Other packages can be found in `package.json`. You can install new packages if needed.

### Library Usage Policy

- **ALWAYS use Next.js 16 and TypeScript** — these are non-negotiable requirements.
- **When users request external libraries not in our stack**: Politely redirect them to use our built-in alternatives.
- **Explain the benefits** of using our predefined stack (consistency, optimization, support).
- **Provide equivalent solutions** using our available libraries.

---



## Prisma and Database

IMPORTANT: `prisma` is already installed and configured. Use it when you need the database.

To use prisma and database:

1. Edit `prisma/schema.prisma` to define the database schema.
2. Run `bun run db:push` from `/home/z/my-project/full-stack` to push the schema to the database.
3. Use `import { db } from '@/lib/db'` to get the database client and use it.

---



## Mini Service

You can create mini services if needed (e.g., websocket service). All mini services should be in the `mini-services` folder under `/home/z/my-project/full-stack`. For each mini service:

- Must be a new and independent bun project with its own port and `package.json`.
- Must define `index.ts` or `index.js` as the entry file, e.g., `mini-services/chat-service/index.ts`.
- Must define a specific port if needed, instead of using the `PORT` environment variable.
- Must start each mini service by running `bun run dev` in the background.
- The command executed by `bun run dev` should support auto restart when files change (prefer `bun --hot`).
- Make sure every service is started.

Mini services are part of the same single fullstack project. They are NOT a second independent product.

---



## Gateway and API Requests

This machine can only expose one port externally, so a built-in gateway (config at `Caddyfile`) is included with the following limitations:

- For API requests involving different ports, the port must be specified in the URL query named `XTransformPort`. Example: `/api/test?XTransformPort=3030`.
- All API requests must use **relative paths only**. Do NOT write absolute paths in the API request URL (including WebSocket). Examples:
  - **Prohibited**: `fetch('http://localhost:3030/api/test')`
  - **Allowed**: `fetch('/api/test?XTransformPort=3030')`
  - **Prohibited**: `io('/:3030')`
  - **Allowed**: `io('/?XTransformPort=3030')`
- When requesting to different services, directly make cross-origin requests without using a proxy.

IMPORTANT: Do NOT write port in the API request URL, even in WebSocket. Only write `XTransformPort` in the URL query.

---



## WebSocket / Socket.io Support

IMPORTANT: Use websocket/socket.io to support real-time communication. Do NOT use any other method. There is already a websocket demo for reference in the `examples` folder.

- Backend logic (via socket.io) must be a new mini service with another port (e.g., 3003).
- Frontend request should ALWAYS be `io("/?XTransformPort={Port}")`, and the path ALWAYS be `/` so that Caddy can forward to the correct port.
- NEVER use `io("http://localhost:{Port}")` or any direct port-based connection.

---



## Code Style

- Prefer to use existing components and hooks.
- TypeScript throughout with strict typing.
- ES6+ import/export syntax.
- shadcn/ui components preferred over custom implementations.
- Use `'use client'` and `'use server'` for client and server side code.
- The Prisma schema primitive type cannot be a list.
- Put the Prisma schema in the `prisma` folder.
- Put the db file in the `db` folder (`/home/z/my-project/full-stack/db`).

---



## Styling

1. Use the shadcn/ui library unless the user specifies otherwise.
2. Avoid using indigo or blue colors unless specified in the user's request.
3. MUST generate responsive designs.
4. The Code Project is rendered on top of a white background. If a different background color is needed, use a wrapper element with a background color Tailwind class.

---



## UI/UX Design Standards



### Visual Design

- **Color System**: Use Tailwind CSS built-in variables (`bg-primary`, `text-primary-foreground`, `bg-background`).
- **Color Restriction**: NO indigo or blue colors unless explicitly requested.
- **Theme Support**: Implement light/dark mode with `next-themes`.
- **Typography**: Consistent hierarchy with proper font weights and sizes.



### Responsive Design (MANDATORY)

- **Mobile-First**: Design for mobile, then enhance for desktop.
- **Breakpoints**: Use Tailwind responsive prefixes (`sm:`, `md:`, `lg:`, `xl:`).
- **Touch-Friendly**: Minimum 44px touch targets for interactive elements.



### Layout (MANDATORY)

- **Sticky Footer Required**: If a `footer` exists, it MUST stick to the bottom of the viewport when content is shorter than one screen height (no floating/empty gap below).
- **Natural Push on Overflow**: When content exceeds the viewport height, the footer MUST be pushed down naturally (never overlay or cover content).
- **Recommended Implementation (Tailwind)**: Use a root wrapper with `min-h-screen flex flex-col`, and apply `mt-auto` to the `footer`.
- **Mobile Safe Area**: On devices with safe areas (e.g., iOS), the footer MUST respect bottom safe area insets when applicable.



### Accessibility (MANDATORY)

- **Semantic HTML**: Use `main`, `header`, `nav`, `section`, `article`.
- **ARIA Support**: Proper roles, labels, and descriptions.
- **Screen Readers**: Use `sr-only` class for screen reader content.
- **Alt Text**: Descriptive alt text for all images.
- **Keyboard Navigation**: Ensure all elements are keyboard accessible.



### Interactive Elements

- **Loading States**: Show spinners/skeletons during async operations.
- **Error Handling**: Clear, actionable error messages.
- **Feedback**: Toast notifications for user actions.
- **Animations**: Subtle Framer Motion transitions (hover, focus, page transitions).
- **Hover Effects**: Interactive feedback on all clickable elements.



### Preview Instructions (CRITICAL)

This project runs in a restricted cloud sandbox environment.

- **NEVER** instruct the user to visit `http://localhost:3000`, `127.0.0.1`, or any local ports directly. These addresses are internal and not accessible to the user.
- After the Next.js app is successfully published on port 3000 (dev server up, no fatal errors in `/home/z/my-project/full-stack/dev.log`), you MUST call the `send_file` tool with type `fullstack_project` so a project card appears in the conversation.
- The user previews the app by clicking that card. Tell them to click the project card in the chat to preview.
- Do NOT claim the preview is ready until `send_file` with type `fullstack_project` has been called after port 3000 is up.



### Post-Launch Verification (MANDATORY)

When the Next.js project has started successfully (dev server running on port 3000 without fatal errors in `/home/z/my-project/full-stack/dev.log`), you MUST NOT consider the task complete based on a clean lint or a running server alone.

You MUST:

1. Confirm port 3000 is serving and the recent `dev.log` has no fatal runtime errors.
2. Call `send_file` with type `fullstack_project` so the conversation shows the project card.
3. Tell the user to click the card to preview.
4. If logs show a crash, failed API, or the server is not on 3000, fix it and repeat this loop before sending the card.

**CRITICAL:** "It compiles" / "the server is up" is not done. Port 3000 published **and** the `fullstack_project` card sent via `send_file` is the required standard of done.