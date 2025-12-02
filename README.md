# Repo Intelligence Agent

AI-inspired DevOps assistant that scans multiple Git repositories, parses their Maven `pom.xml` files, and enables comparison, drift detection, and lightweight natural-language queries.

## Features
- Parallel shallow clone and scan of repositories (JGit)
- Parse `pom.xml` to extract dependencies
- Compare one artifact across repositories
- Version drift detection (min version threshold)
- Matrix comparison (HTML, CSV, XLSX)
- Keyword search
- Lightweight chat endpoint for intent-based queries

## Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/scan` | Clone and scan multiple repos in parallel |
| GET | `/api/compare` | Compare one artifact across repos |
| GET | `/api/compare/table` | HTML side-by-side version table |
| GET | `/api/compare/matrix/table` | HTML matrix comparison |
| GET | `/api/compare/matrix/csv` | CSV export |
| GET | `/api/compare/matrix/xlsx` | Excel export |
| GET | `/api/drift` | Repos below min version |
| GET | `/api/search` | Keyword search |
| GET | `/api/chat` | Natural-language query interpreter |

## Build & Run

```bash
mvn spring-boot:run
```

## Scan Example
```bash
curl -X POST http://localhost:8080/api/scan -H 'Content-Type: application/json' \
  -d '{"repos":["https://github.com/spring-projects/spring-petclinic.git"]}'
```

## Chat Example
```bash
curl 'http://localhost:8080/api/chat?query=Which repos have org.apache.logging.log4j:log4j-core below 2.17.2?'
```

## Notes
- In-memory only; restart loses state.
- Shallow clone depth=1 for speed.
- Version comparison uses Maven ArtifactVersion logic.

## Next Steps
- Add caching for repeated scans
- Add authentication / rate limiting
- Support Gradle build files

