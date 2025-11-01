GitHub Actions Setup Instructions

1. Open: https://github.com/settings/tokens  
2. Click [Generate new token] > [Fine-grained token]  
3. Name the token  
4. Under Repository access, select [Only select repositories] and choose your app’s repository  
5. Under Repository permissions, set [Contents] to [Read and write]  
6. Leave all other permissions as default (No access)  
7. Leave Organization access blank unless using an organization account  
8. Click [Generate token] and copy the token immediately  
9. If only [Classic] token is available, name the token  
10. Set expiration to [No expiration]  
11. Enable scopes: [repo] and optionally [workflow]  
12. Click [Generate token] and copy the token immediately  
13. Go to your repository > Settings > Secrets and variables > Actions  
14. Click [New repository secret]  
15. Set Name to [TOKEN]  
16. Paste the copied token as the Value  
17. Click [Add secret]

Building:

1. Uncomment:
      # - name: Build Debug APK
      #   run: ./gradlew assembleDebug --stacktrace

      # - name: Verify APK output
      #   run: ls -al app/build/outputs/apk/debug

2. Download apk:
https://github.com/[USERNAME]/[PROJECT]/actions