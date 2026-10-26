# Installing in a cloud (AWS, Azure, Google Cloud)

A Command Center in your own cloud account is an on-premises install that happens to run on rented machines: the
same product, your network, your data. This page covers the two usual ways: a Windows virtual machine, or the
Linux container image. Runners that work with the screen stay on Windows machines (virtual or physical); runners for
agents without a screen can run anywhere Windows runs.

## Pieces in any cloud

| Piece | AWS | Azure | Google Cloud |
| --- | --- | --- | --- |
| Database | RDS for SQL Server | Azure SQL Database or SQL Managed Instance | Cloud SQL for SQL Server |
| Vault key (`OOA_MASTER_KEY`) | Secrets Manager | Key Vault secret | Secret Manager |
| Data folder (`/data`, or the install's data folder) | EBS volume, or EFS for several nodes | Managed disk, or Azure Files for several nodes | Persistent disk, or Filestore |
| HTTPS | Application Load Balancer with an ACM certificate, or `ooa tls` | Application Gateway, or `ooa tls` | HTTPS Load Balancer, or `ooa tls` |
| Backups | The database's automated backups plus `ooa backup` to S3 (a mounted bucket or a copy job) | Database backups plus `ooa backup` to Azure Files or Blob | Database backups plus `ooa backup` to Cloud Storage |

Keep the vault key apart from the backups: a backup holds encrypted vault values, never the key.

## A Windows virtual machine

1. Create a Windows Server 2022 machine (4 vCPU, 16 GB to start) in a private subnet.
2. Create the database (SQL Server 2019 or later) and two logins: a setup account that may create tables, and a
   runtime account. Allow the machine to reach the database port.
3. Copy the MSI to the machine and install, as administrator:
   ```
   setx OOA_MASTER_KEY "<from your secret store>" /M
   msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720
   ```
   Then set up the database with `bin\ooa.bat db configure`, `db set-password`, `db set-password --setup`, `db init`
   (see [DATABASE.md](DATABASE.md)), or install with the setup package and `-DbServer`.
4. Put the load balancer in front (HTTPS on 443 to the machine's 8720), or run `ooa tls configure --pfx`.
5. `bin\ooa.bat check` should show no FAIL lines.

## The Linux container image

Build the image once (on a build machine with Docker), from the `platform` folder:
```
gradlew :cli:installCustomerDist
docker build -f docker/Dockerfile -t ooa-command-center:<version> .
```
Push it to your registry (ECR, Azure Container Registry, Artifact Registry). Run it with:

| Setting | Value |
| --- | --- |
| Port | 8720 (put HTTPS on the load balancer) |
| Volume | `/data` on a persistent disk |
| `OOA_MASTER_KEY` | from the secret store |
| `OOA_DB_URL` | for example `jdbc:sqlserver://<server>:1433;databaseName=ooa_command_center;encrypt=true` |
| `OOA_DB_USER`, `OOA_DB_PASSWORD` | the runtime account; the password from the secret store |
| Health check | `GET /api/health` (alive) and `GET /api/health/ready` (ready for traffic) |

Good fits: Amazon ECS on EC2 or Fargate, Azure Container Apps or AKS, Google Kubernetes Engine or Cloud Run with at
least one instance always on (the Command Center runs schedules and triggers in the background, so it must not scale
to zero). `docker/compose.yaml` starts the image with SQL Server for a test. CI builds the image on every push and
checks that it starts on its own and with PostgreSQL.

## Several nodes

Run two or three instances behind the load balancer on the same database. One leads the background work and another
takes over within about 30 seconds if it stops; sign-ins work whichever node a request reaches. Put repository
files on shared storage (Settings > Several nodes > Shared folder, for example an EFS or Azure Files mount).

## Runners

Register runners from Command Center > Runners > Register a runner. In the cloud they reach the Command Center
through its HTTPS address; outbound only, so they can sit in another network as long as they can reach that address.
For pooled virtual desktops tick "Temporary runner".

## Moving from an on-premises install

Stop the old Command Center, make a backup (`bin\ooa.bat backup now`), copy the backup and `master.key` to the new
install, and restore (`bin\ooa.bat backup restore FILE`). Or point `ooa db copy` at the old data folder. Runners keep
their registration if the Command Center's address stays the same; otherwise register them again.
