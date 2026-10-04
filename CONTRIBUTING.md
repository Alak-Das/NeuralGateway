# Contributing to Neural Gateway

Thank you for your interest in contributing to Neural Gateway! We welcome contributions from the community.

## How to Contribute

### Reporting Issues
- Use the GitHub issue tracker to report bugs or suggest features
- Please include:
  - Clear description of the issue
  - Steps to reproduce (if applicable)
  - Expected vs actual behavior
  - Environment details (Java version, OS, etc.)
  - Relevant logs or error messages

### Suggesting Enhancements
- Open an issue describing your proposed enhancement
- Explain the problem it solves and its benefits
- Consider providing use cases or examples

### Contributing Code
1. Fork the repository
2. Create a new branch for your feature or fix (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Ensure your code follows our coding standards
5. Add or update tests as needed
6. Run the test suite to ensure nothing is broken
7. Commit your changes (`git commit -m 'Add amazing feature'`)
8. Push to your branch (`git push origin feature/amazing-feature`)
9. Open a Pull Request against the `main` branch

## Development Setup

### Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose (for Redis)
- Git

### Local Development
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/neural-gateway.git
   cd neural-gateway
   ```

2. Set up environment variables (copy `.env.example` to `.env` and fill in values):
   ```bash
   cp .env.example .env
   # Edit .env with your API keys
   ```

3. Start Redis using Docker Compose:
   ```bash
   docker-compose up -d redis
   ```

4. Run the application:
   ```bash
   ./mvnw spring-boot:run
   ```

### Running Tests
```bash
# Run unit tests
./mvnw test

# Run integration tests
./mvnw verify -DskipITs=false

# Run benchmark tests (see benchmark/README.md)
```

## Coding Standards

### Java
- Follow the existing code style in the repository
- Use meaningful variable and method names
- Add Javadoc comments for public APIs
- Keep methods focused and small
- Handle exceptions appropriately

### Commit Messages
- Use conventional commits format:
  - `feat: add new feature`
  - `fix: resolve issue with X`
  - `docs: update documentation`
  - `refactor: restructure code`
  - `test: add/update tests`
  - `chore: update dependencies`

### Pull Request Process
1. Ensure your PR description clearly describes the changes
2. Link to any related issues
3. Ensure all tests pass
4. Request review from maintainers
5. Address feedback promptly
6. Once approved, maintainers will merge your PR

## Community
- Be respectful and inclusive
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md)
- Help others in the community
- Share your experiences and use cases

## License
By contributing to Neural Gateway, you agree that your contributions will be licensed under the MIT License.