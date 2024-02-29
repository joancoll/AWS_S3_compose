package cat.dam.andy.aws_s3_compose
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions


object AWS_KEYS {
    // TODO: Change this to your own AWS keys
    // AWS CLI CREDENTIALS FOR LAB https://labs.vocareum.com/main/main.php
    // You can get from menu LEARNER LAB / AWS DETAILS / AWS CLI / SHOW Button
    // Change all every time we stop and start the lab
    val AWS_ACCESS_KEY = ""
    val AWS_SECRET_ACCESS_KEY = ""
    val AWS_SESSION_TOKEN =
        ""
    val BUCKET_NAME = ""
    val REGION = Region.getRegion(Regions.US_EAST_1)
}

    //RECORDA donar accés públic i permisos seguents a la carpeta bucket-dades:
    /*
    {
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowAccessToBucket",
            "Effect": "Allow",
            "Principal": "*",
            "Action": [
                "s3:GetObject",
                "s3:PutObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::bucket-dades",
                "arn:aws:s3:::bucket-dades/*"
            ]
        }
    ]
    }

     */