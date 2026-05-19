# Mileage Upload and Route Calculation Flow

## Purpose

This document explains how the mileage upload feature works in the Route Navigator backend. It follows the process from the moment a user uploads an Excel file, through address geocoding, route distance calculation with the Google Routes API, and final report generation.

## Main Files Involved

| File | Responsibility |
| --- | --- |
| `src/main/java/com/routenav/controller/MileageUploadController.java` | Receives the uploaded Excel file through the API. |
| `src/main/java/com/routenav/service/MileageUploadService.java` | Parses the Excel file, groups visits, geocodes addresses, calculates mileage, saves reports, and creates result files. |
| `src/main/java/com/routenav/service/GeocodingService.java` | Converts an address into latitude and longitude coordinates. |
| `src/main/java/com/routenav/service/RoutingService.java` | Calls the Google Routes API to calculate driving distance between two coordinates. |
| `src/main/java/com/routenav/repository/*` | Saves uploads, reports, report rows, visits, and cached routes in the database. |

## High-Level Flow

```text
User uploads Excel file
        |
        v
MileageUploadController receives the file
        |
        v
MileageUploadService reads and parses the Excel rows
        |
        v
Rows are grouped by report month
        |
        v
Each address is geocoded into latitude and longitude
        |
        v
Visits are grouped by staff number
        |
        v
Each staff member's visits are sorted by date and time
        |
        v
Distances are calculated between consecutive visits
        |
        v
Google Routes API returns driving distances
        |
        v
Distances are added together per staff member
        |
        v
Reports, visits, totals, and generated Excel report are saved
```

## Step 1: User Uploads Excel File

The upload starts when the frontend sends a multipart file request to:

```text
POST /api/mileage/upload
```

This endpoint is handled in:

```text
MileageUploadController.java
```

The important method is:

```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file)
```

The controller does not calculate the mileage itself. It passes the uploaded file to:

```java
mileageUploadService.calculateMonthlyMileageUpload(file)
```

## Step 2: Upload Record Is Saved

Inside `MileageUploadService`, the method:

```java
calculateMonthlyMileageUpload(FilePart file)
```

first saves a record of the uploaded file. This allows the system to keep track of uploads and list them later.

After saving the upload, it calls:

```java
calculateMonthlyMileageReports(file, upload.getId())
```

This starts the actual report calculation.

## Step 3: Excel File Is Read Into Memory

The service reads the uploaded Excel file content using:

```java
DataBufferUtils.join(file.content())
```

The file content is converted into bytes, then passed into:

```java
parseVisits(bytes)
```

The Excel parsing uses Apache POI:

```java
Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))
```

## Step 4: Excel Rows Are Parsed Into Visits

The parsing logic is in:

```text
MileageUploadService.java
```

Method:

```java
private List<VisitRow> parseVisits(byte[] bytes)
```

The system reads the first sheet of the workbook:

```java
Sheet sheet = workbook.getSheetAt(0);
```

It expects the first row to contain the column headers.

The required Excel columns are:

```text
staff number
date
visit time
address
```

If any of these columns are missing, the upload fails with an error.

Each non-empty row is converted into a `VisitRow` containing:

```text
staff number
visit date
start time
end time
address
latitude
longitude
```

At this stage, latitude and longitude are still empty because the system has only read the address text from Excel.

## Step 5: Visits Are Grouped By Month

After parsing the Excel file, the system groups visits by month using each visit's date.

The method responsible is:

```java
groupVisitsByMonth(List<VisitRow> visits)
```

This means one uploaded Excel file can produce more than one monthly report.

Example:

```text
Rows from January 2026 -> January 2026 report
Rows from February 2026 -> February 2026 report
Rows from March 2026 -> March 2026 report
```

## Step 6: Addresses Are Converted To Coordinates

Before the system can calculate driving distance, it must convert each address into coordinates.

This is called geocoding.

The method is:

```java
geocodeVisits(List<VisitRow> visits)
```

For each visit address, the service calls:

```java
geocodingService.geocodeFirst(address)
```

The result is a latitude and longitude pair.

Example:

```text
"123 Main Street" -> latitude: -17.8216, longitude: 31.0492
```

The service also caches repeated addresses during the upload:

```java
Map<String, Mono<Location>> addressCache = new HashMap<>();
```

This means if the same address appears many times in the same upload, the system only geocodes it once and reuses the result.

## Step 7: Visits Are Grouped By Staff Number

Once every visit has coordinates, the system groups visits by staff number.

The method is:

```java
calculateMileage(List<VisitRow> visits)
```

It creates a map like:

```text
Staff 1001 -> all visits for staff 1001
Staff 1002 -> all visits for staff 1002
Staff 1003 -> all visits for staff 1003
```

Each staff member's mileage is calculated separately.

## Step 8: Each Staff Member's Visits Are Sorted By Time

For each staff member, the visits are sorted by visit date and start time:

```java
visits.sort(Comparator.comparing(VisitRow::visitDateTime));
```

This is important because mileage depends on the order of travel.

Example:

```text
08:00 - Address A
10:00 - Address B
13:00 - Address C
```

The system assumes the worker traveled:

```text
Address A -> Address B -> Address C
```

## Step 9: Each Day Starts Fresh

After sorting the visits, the system separates each staff member's visits by day.

This prevents the system from calculating a route from the last visit of one day to the first visit of the next day.

Example:

```text
Monday:
08:00 - Address A
10:00 - Address B

Tuesday:
09:00 - Address C
11:00 - Address D
```

The mileage is calculated like this:

```text
Monday mileage = distance(Address A -> Address B)
Tuesday mileage = distance(Address C -> Address D)
```

It is not calculated like this:

```text
Address A -> Address B -> Address C -> Address D
```

So when a new day starts, the first address of that day becomes the starting point for that day.

## Step 10: Consecutive Visit Pairs Are Created

After sorting, the system creates pairs of consecutive locations.

The method is:

```java
calculateLocationDistances(String staffNumber, List<Location> locations)
```

Example:

```text
Visit 1 -> Visit 2
Visit 2 -> Visit 3
Visit 3 -> Visit 4
```

If a staff member has fewer than two valid locations, their mileage is `0.0` because there is no travel leg to calculate.

## Step 11: Google Routes API Calculates Driving Distance

For each pair of locations, the system calls:

```java
routingService.calculateDistanceKm(...)
```

This method is in:

```text
RoutingService.java
```

The `RoutingService` first checks if the same origin and destination route has already been saved in the database.

If a saved route exists, the system reuses the stored distance.

If no saved route exists, the system calls the Google Routes API endpoint:

```text
/directions/v2:computeRoutes
```

The request uses:

```text
travelMode: DRIVE
routingPreference: TRAFFIC_UNAWARE
computeAlternativeRoutes: true
```

Google may return more than one possible route. The backend chooses the shortest returned route by distance.

The returned distance is in meters, so the backend converts it to kilometers:

```java
distanceMeters / 1000.0
```

The distance is then saved in the database so future calculations for the same coordinate pair can reuse it.

## Step 12: Distances Are Added Together

After Google returns the distance for each route pair, the system adds them together:

```java
.reduce(0.0, Double::sum)
```

Example:

```text
Staff 1001:

Address A -> Address B = 4.5 km
Address B -> Address C = 7.2 km
Address C -> Address D = 3.1 km

Total mileage = 14.8 km
```

This total becomes the staff member's driving mileage for that report month.

If a report covers multiple days in the same month, the monthly total is the sum of each day's mileage.

## Step 13: Straight-Line Mileage Is Also Calculated

The system also calculates straight-line distance between coordinates.

This is not the final driving mileage, but it is useful for comparison.

Straight-line distance uses the Haversine formula, which estimates direct distance across the earth's surface.

Driving mileage comes from Google Routes API.

Straight-line mileage comes from local mathematical calculation.

Straight-line mileage is also calculated per day, so it does not connect the last visit of one day to the first visit of the next day.

## Step 14: Report Data Is Saved

After mileage is calculated, the system saves:

```text
The upload record
The monthly report
The staff mileage rows
The individual visit rows
The generated Excel report file
```

The report rows contain each staff member's total mileage.

The visit rows contain the original visit information plus the geocoded coordinates.

## Step 15: A Result Excel File Is Generated

The service creates a new Excel workbook using:

```java
buildWorkbook(results)
```

The generated workbook contains:

```text
Staff Number
Driving Mileage
```

This file is saved with the report and can later be downloaded from:

```text
GET /api/mileage/reports/{id}/download
```

## Simple Example

Suppose the uploaded Excel file contains:

| Staff Number | Date | Visit Time | Address |
| --- | --- | --- | --- |
| 101 | 2026-05-01 | 08:00 | Address A |
| 101 | 2026-05-01 | 10:00 | Address B |
| 101 | 2026-05-01 | 13:00 | Address C |
| 101 | 2026-05-02 | 09:00 | Address D |
| 101 | 2026-05-02 | 11:00 | Address E |

The system groups by staff and by day:

```text
Staff 101:
2026-05-01:
Address A -> Address B -> Address C

2026-05-02:
Address D -> Address E
```

Then it calculates:

```text
Staff 101 mileage =
  distance(A to B)
  + distance(B to C)
  + distance(D to E)
```

Each distance is a driving distance from the Google Routes API.

There is no distance calculated from Address C on 2026-05-01 to Address D on 2026-05-02.

## Important Notes

The system calculates mileage between visits only.

Each day starts fresh. The system does not connect the previous day's last visit to the next day's first visit.

It does not automatically include travel from home to the first visit or from the last visit back home unless those addresses are included as rows in the Excel file.

The order of visits matters. If the visit times are wrong, the mileage route order will also be wrong.

The uploaded Excel headers must match the required names:

```text
staff number
date
visit time
address
```

Repeated addresses are cached during the upload to reduce duplicate geocoding calls.

Route distances are cached in the database so repeated coordinate pairs do not always require another Google Routes API call.

## Summary

The mileage upload feature works by turning Excel visit rows into ordered travel legs for each staff member. Addresses are converted into coordinates, consecutive visits are sent to Google Routes API for driving distance, and the returned distances are summed into monthly staff mileage totals.

In short:

```text
Excel rows -> visits -> coordinates -> ordered staff routes -> Google driving distances -> monthly mileage report
```
