# Neural Gateway UI Flow Diagram

This document contains Mermaid diagrams illustrating the UI architecture, data flow, component relationships, and user interaction flows.

---

## 1. High-Level Architecture

```mermaid
graph TB
    subgraph "Client Browser"
        A[React App Entry<br/>main.tsx] --> B[App Component<br/>App.tsx]
        B --> C[Theme System<br/>Dark/Light Mode]
        B --> D[SSE Connection Manager<br/>EventSource]
        B --> E[State Management<br/>useState/useRef]
        
        D --> F[SSE Events<br/>init/status]
        D --> G[Connection Status<br/>CONNECTING/LIVE/RECONNECTING/DISCONNECTED]
        
        B --> H[Layout Components]
        H --> I[Navbar<br/>Brand, Status, Theme Toggle]
        H --> J[Main Content Area]
        
        J --> K[KpiGrid<br/>Dashboard Metrics]
        J --> L[Charts<br/>Latency + Usage]
        J --> M[Tab Navigation<br/>Models/Requesters]
        M --> N[StatusTable<br/>Model Details]
        M --> O[RequestersTable<br/>Requester Telemetry]
    end
    
    subgraph "Backend Services"
        P[Spring Boot API<br/>Port 8080]
        Q[SSE Endpoint<br/>/api/models/status/stream]
        R[REST Endpoint<br/>/api/requesters/status]
    end
    
    D -.->|Server-Sent Events| Q
    F -.->|fetch()| R
    
    style A fill:#e1f5fe
    style B fill:#f3e5f5
    style D fill:#fff3e0
    style P fill:#e8f5e9
    style Q fill:#e8f5e9
    style R fill:#e8f5e9
```

---

## 2. Component Hierarchy & Data Flow

```mermaid
graph TD
    subgraph "App.tsx (Root)"
        App[App Component]
        
        subgraph "State"
            S1[theme: 'dark'|'light']
            S2[connectionStatus: Enum]
            S3[data: ModelStatus[]]
            S4[requesters: RequesterStatus[]]
            S5[lastUpdated: Date|null]
            S6[activeTab: 'models'|'requesters']
        end
        
        subgraph "Refs"
            R1[eventSourceRef: EventSource]
            R2[reconnectAttemptsRef: number]
        end
    end
    
    subgraph "Components"
        KPI[KpiGrid.tsx]
        CHARTS[Charts.tsx]
        TABS[Tab Navigation]
        STABLE[StatusTable.tsx]
        RTABLE[RequestersTable.tsx]
    end
    
    App -->|data, lastUpdated| KPI
    App -->|data| CHARTS
    App -->|data| STABLE
    App -->|requesters| RTABLE
    App -->|activeTab| TABS
    TABS -->|controls| STABLE
    TABS -->|controls| RTABLE
    
    subgraph "External APIs"
        SSE[SSE Stream<br/>/api/models/status/stream]
        REST[REST API<br/>/api/requesters/status]
    end
    
    App -->|EventSource| SSE
    App -->|fetch()| REST
    
    SSE -->|init event| S3
    SSE -->|status event| S3
    REST -->|response| S4
    
    style App fill:#f3e5f5
    style KPI fill:#e1f5fe
    style CHARTS fill:#e1f5fe
    style STABLE fill:#e1f5fe
    style RTABLE fill:#e1f5fe
    style SSE fill:#fff3e0
    style REST fill:#fff3e0
```

---

## 3. SSE Connection Lifecycle

```mermaid
stateDiagram-v2
    [*] --> CONNECTING: App Mount
    CONNECTING --> LIVE: SSE onopen
    CONNECTING --> RECONNECTING: Error (attempt < 10)
    LIVE --> RECONNECTING: SSE onerror
    RECONNECTING --> CONNECTING: Retry Delay Expires
    RECONNECTING --> LIVE: SSE onopen
    RECONNECTING --> DISCONNECTED: Max Attempts (10) Reached
    LIVE --> DISCONNECTED: Max Attempts (10) Reached
    CONNECTING --> DISCONNECTED: Max Attempts (10) Reached
    
    state CONNECTING {
        [*] --> CreatingEventSource
        CreatingEventSource --> WaitingForOpen
    }
    
    state RECONNECTING {
        [*] --> IncrementingCounter
        IncrementingCounter --> CalculatingBackoff
        CalculatingBackoff --> WaitingForTimeout
        WaitingForTimeout --> CreatingEventSource
    }
    
    state LIVE {
        [*] --> ListeningForEvents
        ListeningForEvents --> ProcessingInit: init event
        ListeningForEvents --> ProcessingStatus: status event
        ProcessingInit --> UpdatingState
        ProcessingStatus --> UpdatingState
        UpdatingState --> ListeningForEvents
    }
    
    DISCONNECTED --> [*]: Cleanup on Unmount
```

---

## 4. User Interaction Flows

```mermaid
flowchart TD
    Start([User Opens App]) --> ThemeInit{Theme Init}
    ThemeInit --> LoadSaved[Load from localStorage]
    ThemeInit --> SystemPref[Use System Preference]
    LoadSaved --> ApplyTheme[Apply to document]
    SystemPref --> ApplyTheme
    ApplyTheme --> SSEConnect[Establish SSE Connection]
    
    SSEConnect --> ConnState{Connection State}
    ConnState -->|LIVE| RenderDashboard[Render Dashboard]
    ConnState -->|CONNECTING| ShowConnecting[Show Connecting...]
    ConnState -->|RECONNECTING| ShowReconnecting[Show Reconnecting...]
    ConnState -->|DISCONNECTED| ShowDisconnected[Show Disconnected]
    
    RenderDashboard --> UserAction{User Action}
    
    UserAction -->|Theme Toggle| ToggleTheme[Toggle Theme]
    ToggleTheme --> UpdateTheme[Update State + DOM + localStorage]
    UpdateTheme --> RenderDashboard
    
    UserAction -->|Tab Click| SwitchTab[Switch Tab]
    SwitchTab --> UpdateTab[Update activeTab State]
    UpdateTab --> RenderDashboard
    
    UserAction -->|Sort Column| SortTable[Sort Table]
    SortTable --> UpdateSort[Update sortCol/sortDir]
    UpdateTab --> RenderDashboard
    
    UserAction -->|Filter Category| FilterTable[Filter Category]
    FilterTable --> UpdateFilter[Update categoryFilter]
    UpdateFilter --> RenderDashboard
    
    UserAction -->|Filter Status| FilterStatus[Filter Status]
    FilterStatus --> UpdateStatusFilter[Update statusFilter]
    UpdateStatusFilter --> RenderDashboard
    
    UserAction -->|Time Range| ChangeRange[Change Time Range]
    ChangeRange --> UpdateRange[Update rangeMins]
    UpdateRange --> RecomputeCharts[Recompute Chart Data]
    RecomputeCharts --> RenderDashboard
    
    SSEConnect -.->|SSE Events| UpdateData[Update Model Data]
    UpdateData --> FetchRequesters[Fetch Requesters]
    FetchRequesters --> UpdateRequesters[Update Requester Data]
    UpdateRequesters --> RenderDashboard
```

---

## 5. Data Models & Type Definitions

```mermaid
classDiagram
    class ModelStatus {
        +model: string
        +categories: string[]
        +isUp: boolean
        +circuitOpen: boolean
        +latencyMs: number
        +tps: number
        +totalUses: number
        +activeConnections: number
        +lastChecked: string
        +errorMessage: string
        +history: HistoryPoint[]
    }
    
    class RequesterStatus {
        +requester: string
        +count: number
    }
    
    class HistoryPoint {
        +timestamp: string
        +isUp: boolean
        +latencyMs: number
    }
    
    ModelStatus "1" --> "*" HistoryPoint : contains
    
    class KpiGridProps {
        +data: ModelStatus[]
        +lastUpdated: Date|null
    }
    
    class ChartsProps {
        +data: ModelStatus[]
    }
    
    class StatusTableProps {
        +data: ModelStatus[]
    }
    
    class RequestersTableProps {
        +requesters: RequesterStatus[]
    }
    
    App --> KpiGridProps : passes data, lastUpdated
    App --> ChartsProps : passes data
    App --> StatusTableProps : passes data
    App --> RequestersTableProps : passes requesters
```

---

## 6. Theme System Flow

```mermaid
sequenceDiagram
    participant User
    participant App
    participant DOM
    participant LocalStorage
    participant SystemMediaQuery
    
    Note over App: Initial Mount
    App->>LocalStorage: getItem('theme')
    alt Theme in localStorage
        LocalStorage-->>App: 'dark' or 'light'
    else No saved theme
        App->>SystemMediaQuery: matchMedia('(prefers-color-scheme: dark)')
        SystemMediaQuery-->>App: true/false
    end
    App->>DOM: document.documentElement.dataset.theme = theme
    App->>DOM: document.documentElement.setAttribute('data-bs-theme', theme)
    App->>App: setState(theme)
    
    Note over User,App: User Clicks Theme Toggle
    User->>App: toggleTheme()
    App->>App: newTheme = theme === 'dark' ? 'light' : 'dark'
    App->>DOM: document.documentElement.dataset.theme = newTheme
    App->>DOM: document.documentElement.setAttribute('data-bs-theme', newTheme)
    App->>LocalStorage: setItem('theme', newTheme)
    App->>App: setTheme(newTheme)
```

---

## 7. Chart Data Processing Pipeline

```mermaid
flowchart LR
    subgraph "Charts.tsx"
        Input[ModelStatus[] data]
        Input --> Filter[Filter by rangeMins]
        Filter --> Bucket[Bucket into 1-min intervals]
        Bucket --> Align[Align timestamps across models]
        Align --> Dataset[Create Chart.js datasets]
        
        subgraph "Latency Chart (Line)"
            Dataset --> LConfig[Configure: tension, colors, fill]
            LConfig --> LChart[Line Chart Component]
        end
        
        subgraph "Usage Chart (Doughnut)"
            Dataset --> UConfig[Configure: colors, center text]
            UConfig --> UChart[Doughnut Chart Component]
        end
    end
    
    subgraph "Chart.js Config"
        LChart --> LAnim[Animation: duration 800ms, stagger]
        UChart --> UAnim[Animation: count-up center text]
    end
    
    LChart --> Render[Render to Canvas]
    UChart --> Render
    
    style Input fill:#e1f5fe
    style LChart fill:#fff3e0
    style UChart fill:#fff3e0
```

---

## 8. Responsive Layout Breakpoints

```mermaid
graph LR
    subgraph "Bootstrap Grid System"
        XL["xl (≥1200px)\n4 columns"]
        LG["lg (≥992px)\n4 columns"]
        MD["md (≥768px)\n2 columns"]
        SM["sm (≥576px)\n2 columns"]
        XS["xs (<576px)\n1 column"]
    end
    
    subgraph "KPI Grid Cards"
        Card1[Fleet Health]
        Card2[Global TPS]
        Card3[Active Model]
        Card4[Active Connections]
        Card5[Avg Latency]
    end
    
    XL --> Card1
    XL --> Card2
    XL --> Card3
    XL --> Card4
    XL --> Card5
    
    LG --> Card1
    LG --> Card2
    LG --> Card3
    LG --> Card4
    LG --> Card5
    
    MD --> Card1
    MD --> Card2
    MD --> Card3
    MD --> Card4
    MD --> Card5
    
    SM --> Card1
    SM --> Card2
    SM --> Card3
    SM --> Card4
    SM --> Card5
    
    XS --> Card1
    XS --> Card2
    XS --> Card3
    XS --> Card4
    XS --> Card5
```

---

## 9. Error Handling & Edge Cases

```mermaid
flowchart TD
    Error[Error Occurs] --> Type{Error Type}
    
    Type -->|SSE Parse Error| ParseErr[console.error + continue]
    Type -->|SSE Connection Error| ConnErr[Increment Reconnect Counter]
    Type -->|Fetch Requesters Failed| FetchErr[Silent Fail - Keep Old Data]
    Type -->|Chart Data Empty| EmptyChart[Show Empty State]
    Type -->|Table Data Empty| EmptyTable[Show 'No data' Row]
    Type -->|Theme Toggle Error| ThemeErr[Revert to Previous]
    
    ConnErr --> CheckMax{Attempts ≥ 10?}
    CheckMax -->|Yes| Disconnected[Set DISCONNECTED]
    CheckMax -->|No| ScheduleRetry[setTimeout with Exponential Backoff]
    ScheduleRetry --> Reconnect[Call connectSSE()]
    
    Disconnected --> UserAction[Wait for User Refresh]
    
    style Error fill:#ffebee
    style Disconnected fill:#ffebee
    style ParseErr fill:#fff3e0
    style ConnErr fill:#fff3e0
```

---

## 10. Animation Flow (Proposed)

```mermaid
sequenceDiagram
    participant User
    participant App
    participant FramerMotion
    participant Components
    
    Note over App: Page Load
    App->>FramerMotion: Mount with initial="hidden"
    FramerMotion->>Components: Animate entrance
    Components-->>User: Staggered slide-up cards
    
    Note over User: Tab Switch
    User->>App: Click Requesters Tab
    App->>FramerMotion: AnimatePresence exit current
    FramerMotion->>Components: Fade out + slide up
    App->>App: setState(activeTab='requesters')
    FramerMotion->>Components: Fade in + slide down
    Components-->>User: Smooth tab transition
    
    Note over User: Theme Toggle
    User->>App: Click Theme Button
    App->>App: Toggle theme state
    FramerMotion->>Components: Background cross-fade
    FramerMotion->>Components: Content blur + fade
    Components-->>User: Smooth theme transition
    
    Note over System: Data Update (SSE)
    System->>App: New ModelStatus[] via SSE
    App->>Components: Re-render with new data
    FramerMotion->>Components: Layout animation (table rows)
    FramerMotion->>Components: Cell flash (changed values)
    Components-->>User: Visual feedback on changes
```

---

## How to View These Diagrams

1. **VS Code**: Install "Markdown Preview Mermaid Support" extension
2. **GitHub/GitLab**: Diagrams render automatically in markdown files
3. **Obsidian/Notion**: Native Mermaid support
4. **Online**: Copy into [Mermaid Live Editor](https://mermaid.live/)

---

## Legend

| Color | Meaning |
|-------|---------|
| 🔵 Light Blue | UI Components |
| 🟣 Purple | Root/State Management |
| 🟠 Orange | External APIs/Connections |
| 🟢 Green | Backend Services |
| 🔴 Red | Error States |
| 🟡 Yellow | Warning/Intermediate States |