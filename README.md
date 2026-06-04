# zhj_route

Route planning demo with a Spring Boot backend and Vue frontend.

## Structure

- `backend`: Spring Boot API. It connects to the existing `ljszy_new` database and cross-schema `cloud_management`.
- `frontend`: Vue 3 demo page. It displays companies, routes, route records, and an optimization preview.

## Backend

```powershell
cd D:\projects\zhj_route\backend
mvn spring-boot:run
```

Default API base URL:

```text
http://localhost:8088/api
```

## Frontend

```powershell
cd D:\projects\zhj_route\frontend
npm install
npm run dev
```

Default frontend URL:

```text
http://localhost:5173
```

## Current Demo Scope

The backend currently exposes:

- companies from `cloud_management.cloud_department`
- route plans from `ljszy_route_info`
- route records from `ljszy_route_record`
- route record facility points from monthly split tables
- a placeholder route optimization endpoint

The real optimization algorithm can be added later behind `POST /api/optimize/preview`.
