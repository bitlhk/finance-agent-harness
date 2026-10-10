# Customer CRM read-only Provider

This Provider reads synthetic customer facts directly from the approved CRM service. It does not
call an Employee Agent application and does not perform CRM writes.

Access is admitted only when the customer is present in both the deployment-owned employee mapping
and an active CRM assignment for the mapped employee principal. Service credentials remain in the
deployment environment and are never included in build assets, model input, reports, or receipts.
