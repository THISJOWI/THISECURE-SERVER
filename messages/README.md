# NestJS Cassandra Messages

This project is a NestJS application that manages messages using Apache Cassandra as the database. It provides a RESTful API for creating, retrieving, updating, and deleting messages.

## Features

- **Cassandra Integration**: Utilizes Apache Cassandra for storing messages.
- **RESTful API**: Exposes endpoints for message management.
- **DTOs**: Implements Data Transfer Objects for structured data handling.

## Project Structure

```
nestjs-cassandra-messages
├── src
│   ├── app.module.ts          # Root module of the application
│   ├── main.ts                # Entry point of the application
│   ├── cassandra              # Module for Cassandra integration
│   │   ├── cassandra.module.ts # Module definition for Cassandra
│   │   └── cassandra.service.ts # Service for Cassandra operations
│   └── messages               # Module for message management
│       ├── dto
│       │   └── create-message.dto.ts # DTO for creating messages
│       ├── messages.controller.ts    # Controller for message endpoints
│       ├── messages.module.ts         # Module definition for messages
│       └── messages.service.ts        # Service for message operations
├── test
│   ├── app.e2e-spec.ts        # End-to-end tests for the application
│   └── jest-e2e.json          # Jest configuration for end-to-end tests
├── nest-cli.json              # Nest CLI configuration
├── package.json                # NPM configuration
├── tsconfig.json              # TypeScript configuration
└── README.md                  # Project documentation
```

## Installation

1. Clone the repository:
   ```
   git clone <repository-url>
   cd nestjs-cassandra-messages
   ```

2. Install dependencies:
   ```
   npm install
   ```

3. Set up your Apache Cassandra database and configure the connection in the `cassandra.service.ts` file.

## Running the Application

To start the application, run:
```
npm run start
```

The application will be available at `http://localhost:3000`.

## API Endpoints

- **POST /messages**: Create a new message.
- **GET /messages**: Retrieve all messages.
- **GET /messages/:id**: Retrieve a message by ID.
- **PUT /messages/:id**: Update a message by ID.
- **DELETE /messages/:id**: Delete a message by ID.

## Testing

To run the end-to-end tests, use:
```
npm run test:e2e
```

## License

This project is licensed under the MIT License.